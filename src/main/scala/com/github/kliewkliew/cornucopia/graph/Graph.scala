package com.github.kliewkliew.cornucopia.graph

import java.util
import java.util.concurrent.atomic.AtomicInteger

import akka.actor._
import akka.stream.{ClosedShape, FlowShape, ThrottleMode}
import com.github.kliewkliew.cornucopia.redis.Connection.{CodecType, Salad, getConnection, newSaladAPI}
import org.slf4j.LoggerFactory
import org.apache.kafka.clients.consumer.ConsumerRecord
import com.github.kliewkliew.cornucopia.redis._
import com.github.kliewkliew.salad.SaladClusterAPI
import com.lambdaworks.redis.RedisURI
import com.lambdaworks.redis.cluster.models.partitions.RedisClusterNode
import com.lambdaworks.redis.models.role.RedisInstance.Role

import collection.JavaConverters._
import scala.collection.mutable
import scala.collection.JavaConversions._
import scala.language.implicitConversions
import scala.concurrent.{ExecutionContext, Future}
import akka.stream.scaladsl.{Flow, GraphDSL, Merge, MergePreferred, Partition, RunnableGraph, Sink, Broadcast}
import com.github.kliewkliew.cornucopia.Config
// TO-DO: put config someplace else

trait CornucopiaGraph {
  import scala.concurrent.ExecutionContext.Implicits.global

  private val logger = LoggerFactory.getLogger(this.getClass)

  def partitionEvents(key: String) = key.trim.toLowerCase match {
    case ADD_MASTER.key     => ADD_MASTER.ordinal
    case ADD_SLAVE.key      => ADD_SLAVE.ordinal
    case REMOVE_NODE.key    => REMOVE_NODE.ordinal
    case RESHARD.key        => RESHARD.ordinal
    case _                  => UNSUPPORTED.ordinal
  }

  def partitionNodeRemoval(key: String) = key.trim.toLowerCase match {
    case REMOVE_MASTER.key  => REMOVE_MASTER.ordinal
    case REMOVE_SLAVE.key   => REMOVE_SLAVE.ordinal
    case UNSUPPORTED.key    => UNSUPPORTED.ordinal
  }

  /**
    * Stream definitions for the graph.
    */
  // Extract a tuple of the key and value from a Kafka record.
  case class KeyValue(key: String, value: String, senderRef: Option[ActorRef] = None)

  // Add a master node to the cluster.
  def streamAddMaster(implicit executionContext: ExecutionContext) = Flow[KeyValue]
    .map(_.value)
    .map(RedisURI.create)
    .map(newSaladAPI.canonicalizeURI)
    .groupedWithin(100, Config.Cornucopia.batchPeriod)
    .mapAsync(1)(addNodesToCluster)
    .mapAsync(1)(waitForTopologyRefresh[Seq[RedisURI]])
    .map(_ => KeyValue(RESHARD.key, ""))

  // Add a slave node to the cluster, replicating the master that has the fewest slaves.
  protected def streamAddSlave(implicit executionContext: ExecutionContext) = Flow[KeyValue]
    .map(_.value)
    .map(RedisURI.create)
    .map(newSaladAPI.canonicalizeURI)
    .groupedWithin(100, Config.Cornucopia.batchPeriod)
    .mapAsync(1)(addNodesToCluster)
    .mapAsync(1)(waitForTopologyRefresh[Seq[RedisURI]])
    .mapAsync(1)(findMasters)
    .mapAsync(1)(waitForTopologyRefresh[Unit])
    .mapAsync(1)(_ => logTopology)
    .map(_ => KeyValue("", ""))

  // Emit a key-value pair indicating the node type and URI.
  protected def streamRemoveNode(implicit executionContext: ExecutionContext) = Flow[KeyValue]
    .map(_.value)
    .map(RedisURI.create)
    .map(newSaladAPI.canonicalizeURI)
    .mapAsync(1)(emitNodeType)

  // Remove a slave node from the cluster.
  protected def streamRemoveSlave(implicit executionContext: ExecutionContext) = Flow[KeyValue]
    .map(_.value)
    .groupedWithin(100, Config.Cornucopia.batchPeriod)
    .mapAsync(1)(forgetNodes)
    .mapAsync(1)(waitForTopologyRefresh[Unit])
    .mapAsync(1)(_ => logTopology)
    .map(_ => KeyValue("", ""))

  // Redistribute the hash slots among all nodes in the cluster.
  // Execute slot redistribution at most once per configured interval.
  // Combine multiple requests into one request.
  protected def streamReshard(implicit executionContext: ExecutionContext) = Flow[KeyValue]
    .map(record => Seq(record.value))
    .conflate((seq1, seq2) => seq1 ++ seq2)
    .throttle(1, Config.Cornucopia.minReshardWait, 1, ThrottleMode.Shaping)
    .mapAsync(1)(reshardCluster)
    .mapAsync(1)(waitForTopologyRefresh[Unit])
    .mapAsync(1)(_ => logTopology)
    .map(_ => KeyValue("", ""))

  // Throw for keys indicating unsupported operations.
  protected def unsupportedOperation = Flow[KeyValue]
    .map(record => throw new IllegalArgumentException(s"Unsupported operation ${record.key} for ${record.value}"))

  /**
    * Wait for the new cluster topology view to propagate to all nodes in the cluster. May not be strictly necessary
    * since this microservice immediately attempts to notify all nodes of topology updates.
    *
    * @param passthrough The value that will be passed through to the next map stage.
    * @param executionContext The thread dispatcher context.
    * @tparam T
    * @return The unmodified input value.
    */
  protected def waitForTopologyRefresh[T](passthrough: T)(implicit executionContext: ExecutionContext): Future[T] = Future {
    scala.concurrent.blocking(Thread.sleep(Config.Cornucopia.refreshTimeout))
    passthrough
  }

  /**
    * Wait for the new cluster topology view to propagate to all nodes in the cluster. Same version as above, but this
    * time takes two passthroughs and returns tuple of them as future.
    *
    * @param passthrough1 The first value that will be passed through to the next map stage.
    * @param passthrough2 The second value that will be passed through to the next map stage.
    * @param executionContext The thread dispatcher context.
    * @tparam T
    * @tparam U
    * @return The unmodified input value.
    */
  protected def waitForTopologyRefresh2[T, U](passthrough1: T, passthrough2: U)(implicit executionContext: ExecutionContext): Future[(T, U)] = Future {
    scala.concurrent.blocking(Thread.sleep(Config.Cornucopia.refreshTimeout))
    (passthrough1, passthrough2)
  }

  /**
    * Log the current view of the cluster topology.
    *
    * @param executionContext The thread dispatcher context.
    * @return
    */
  protected def logTopology(implicit executionContext: ExecutionContext): Future[Unit] = {
    implicit val saladAPI = newSaladAPI
    saladAPI.clusterNodes.map { allNodes =>
      val masterNodes = allNodes.filter(Role.MASTER == _.getRole)
      val slaveNodes = allNodes.filter(Role.SLAVE == _.getRole)
      logger.info(s"Master nodes: $masterNodes")
      logger.info(s"Slave nodes: $slaveNodes")
    }
  }

  /**
    * The entire cluster will meet the new nodes at the given URIs.
    *
    * @param redisURIList The list of URI of the new nodes.
    * @param executionContext The thread dispatcher context.
    * @return The list of URI if the nodes were met. TODO: emit only the nodes that were successfully added.
    */
  protected def addNodesToCluster(redisURIList: Seq[RedisURI])(implicit executionContext: ExecutionContext): Future[Seq[RedisURI]] = {
    implicit val saladAPI = newSaladAPI
    saladAPI.clusterNodes.flatMap { allNodes =>
      val getConnectionsToLiveNodes = allNodes.filter(_.isConnected).map(node => getConnection(node.getNodeId))
      Future.sequence(getConnectionsToLiveNodes).flatMap { connections =>
        // Meet every new node from every old node.
        val metResults = for {
          conn <- connections
          uri <- redisURIList
        } yield {
          conn.clusterMeet(uri)
        }
        Future.sequence(metResults).map(_ => redisURIList)
      }
    }
  }

  /**
    * Set the n new slave nodes to replicate the poorest (fewest slaves) n masters.
    *
    * @param redisURIList The list of ip addresses of the slaves that will be added to the cluster. Hostnames are not acceptable.
    * @param executionContext The thread dispatcher context.
    * @return Indicate that the n new slaves are replicating the poorest n masters.
    */
  protected def findMasters(redisURIList: Seq[RedisURI])(implicit executionContext: ExecutionContext): Future[Unit] = {
    implicit val saladAPI = newSaladAPI
    saladAPI.clusterNodes.flatMap { allNodes =>
      // Node ids for nodes that are currently master nodes but will become slave nodes.
      val newSlaveIds = allNodes.filter(node => redisURIList.contains(node.getUri)).map(_.getNodeId)
      // The master nodes (the nodes that will become slaves are still master nodes at this point and must be filtered out).
      val masterNodes = saladAPI.masterNodes(allNodes)
        .filterNot(node => newSlaveIds.contains(node.getNodeId))
      // HashMap of master node ids to the number of slaves for that master.
      val masterSlaveCount = new util.HashMap[String, AtomicInteger](masterNodes.length + 1, 1)
      // Populate the hash map.
      masterNodes.map(_.getNodeId).foreach(nodeId => masterSlaveCount.put(nodeId, new AtomicInteger(0)))
      allNodes.map { node =>
        Option.apply(node.getSlaveOf)
          .map(master => masterSlaveCount.get(master).incrementAndGet())
      }

      // Find the poorest n masters for n slaves.
      val poorestMasters = new MaxNHeapMasterSlaveCount(redisURIList.length)
      masterSlaveCount.asScala.foreach(poorestMasters.offer)
      assert(redisURIList.length >= poorestMasters.underlying.length)

      // Create a list so that we can circle back to the first element if the new slaves outnumber the existing masters.
      val poorMasterList = poorestMasters.underlying.toList
      val poorMasterIndex = new AtomicInteger(0)
      // Choose a master for every slave.
      val listFuturesResults = redisURIList.map { slaveURI =>
        getConnection(slaveURI).map(_.clusterReplicate(
          poorMasterList(poorMasterIndex.getAndIncrement() % poorMasterList.length)._1))
      }
      Future.sequence(listFuturesResults).map(x => x)
    }
  }

  /**
    * Emit a key-value representing the node-type and the node-id.
    * @param redisURI
    * @param executionContext
    * @return the node type and id.
    */
  def emitNodeType(redisURI:RedisURI)(implicit executionContext: ExecutionContext): Future[KeyValue] = {
    implicit val saladAPI = newSaladAPI
    saladAPI.clusterNodes.map { allNodes =>
      val removalNodeOpt = allNodes.find(node => node.getUri.equals(redisURI))
      if (removalNodeOpt.isEmpty) throw new Exception(s"Node not in cluster: $redisURI")
      val kv = removalNodeOpt.map { node =>
        node.getRole match {
          case Role.MASTER => KeyValue(RESHARD.key, node.getNodeId)
          case Role.SLAVE  => KeyValue(REMOVE_SLAVE.key, node.getNodeId)
          case _           => KeyValue(UNSUPPORTED.key, node.getNodeId)
        }
      }
      kv.get
    }
  }

  /**
    * Safely remove a master by redistributing its hash slots before blacklisting it from the cluster.
    * The data is given time to migrate as configured in `cornucopia.grace.period`.
    *
    * @param withoutNodes The list of ids of the master nodes that will be removed from the cluster.
    * @param executionContext The thread dispatcher context.
    * @return Indicate that the hash slots were redistributed and the master removed from the cluster.
    */
  protected def removeMasters(withoutNodes: Seq[String])(implicit executionContext: ExecutionContext): Future[Unit] = {
    val reshardDone = reshardCluster(withoutNodes).map { _ =>
      scala.concurrent.blocking(Thread.sleep(Config.Cornucopia.gracePeriod)) // Allow data to migrate.
    }
    reshardDone.flatMap(_ => forgetNodes(withoutNodes))
  }

  /**
    * Notify all nodes in the cluster to forget this node.
    *
    * @param withoutNodes The list of ids of nodes to be forgotten by the cluster.
    * @param executionContext The thread dispatcher context.
    * @return A future indicating that the node was forgotten by all nodes in the cluster.
    */
  def forgetNodes(withoutNodes: Seq[String])(implicit executionContext: ExecutionContext): Future[Unit] =
    if (!withoutNodes.exists(_.nonEmpty))
      Future(Unit)
    else {
      implicit val saladAPI = newSaladAPI
      saladAPI.clusterNodes.flatMap { allNodes =>
        logger.info(s"Forgetting nodes: $withoutNodes")
        // Reset the nodes to be removed.
        val validWithoutNodes = withoutNodes.filter(_.nonEmpty)
        validWithoutNodes.map(getConnection).map(_.map(_.clusterReset(true)))

        // The nodes that will remain in the cluster should forget the nodes that will be removed.
        val withNodes = allNodes
          .filterNot(node => validWithoutNodes.contains(node.getNodeId)) // Node cannot forget itself.

        // For the cross product of `withNodes` and `withoutNodes`; to remove the nodes in `withoutNodes`.
        val forgetResults = for {
          operatorNode <- withNodes
          operandNodeId <- validWithoutNodes
        } yield {
          if (operatorNode.getSlaveOf == operandNodeId)
            Future(Unit) // Node cannot forget its master.
          else
            getConnection(operatorNode.getNodeId).flatMap(_.clusterForget(operandNodeId))
        }
        Future.sequence(forgetResults).map(x => x)
      }
    }

  /**
    * Reshard the cluster using a view of thecluster consisting of a subset of master nodes.
    *
    * @param withoutNodes The list of ids of nodes that will not be assigned hash slots. Note that this is not used
    *                     (it is empty) when we add a new master node and reshard.
    * @return Boolean indicating that all hash slots were reassigned successfully.
    */
  protected def reshardCluster(withoutNodes: Seq[String])
  : Future[Unit] = {
    // Execute futures using a thread pool so we don't run out of memory due to futures.
    implicit val executionContext = Config.Consumer.actorSystem.dispatchers.lookup("akka.actor.resharding-dispatcher")
    implicit val saladAPI = newSaladAPI
    saladAPI.masterNodes.flatMap { masterNodes =>

      val liveMasters = masterNodes.filter(_.isConnected)
      lazy val idToURI = new util.HashMap[String,RedisURI](liveMasters.length + 1, 1)
      // Re-use cluster connections so we don't exceed file-handle limit or waste resources.
      lazy val clusterConnections = new util.HashMap[String,Future[SaladClusterAPI[CodecType,CodecType]]](liveMasters.length + 1, 1)
      liveMasters.map { master =>
        idToURI.put(master.getNodeId, master.getUri)
        clusterConnections.put(master.getNodeId, getConnection(master.getNodeId))
      }

      // Remove dead nodes. This may generate WARN logs if some nodes already forgot the dead node.
      val deadMastersIds = masterNodes.filterNot(_.isConnected).map(_.getNodeId)
      logger.info(s"Dead nodes: $deadMastersIds")
      forgetNodes(deadMastersIds)

      // Migrate the data.
      val assignableMasters = liveMasters.filterNot(masterNode => withoutNodes.contains(masterNode.getNodeId))
      logger.info(s"Resharding cluster with ${assignableMasters.map(_.getNodeId)} without ${withoutNodes ++ deadMastersIds}")
      val migrateResults = liveMasters.flatMap { node =>
        val (sourceNodeId, slotList) = (node.getNodeId, node.getSlots.toList.map(_.toInt))
        logger.debug(s"Migrating data from $sourceNodeId among slots $slotList")
        slotList.map { slot =>
          val destinationNodeId = slotNode(slot, assignableMasters)
          migrateSlot(
            slot,
            sourceNodeId, destinationNodeId, idToURI.get(destinationNodeId),
            assignableMasters, clusterConnections)
        }
      }
      val finalMigrateResult = Future.sequence(migrateResults)
      finalMigrateResult.onFailure { case e => logger.error(s"Failed to migrate hash slot data", e) }
      finalMigrateResult.onSuccess { case _ => logger.info(s"Migrated hash slot data") }
      // We attempted to migrate the data but do not prevent slot reassignment if migration fails.
      // We may lose prior data but we ensure that all slots are assigned.
      finalMigrateResult.onComplete { case _ =>
        List.range(0, 16384).map(notifySlotAssignment(_, assignableMasters))
      }
      finalMigrateResult.flatMap(_ => forgetNodes(withoutNodes))
    }
  }

  // TODO: pass slotNode as a lambda to migrateSlot and notifySlotAssignment.
  // TODO: more efficient slot assignment to prevent data migration.
  /**
    * Choose a master node for a slot.
    *
    * @param slot The slot to be assigned.
    * @param masters The list of masters that can be assigned slots.
    * @return The node id of the chosen master.
    */
  protected def slotNode(slot: Int, masters: mutable.Buffer[RedisClusterNode]): String =
    masters(slot % masters.length).getNodeId

  /**
    * Migrate all keys in a slot from the source node to the destination node and update the slot assignment on the
    * affected nodes.
    *
    * @param slot The slot to migrate.
    * @param sourceNodeId The current location of the slot data.
    * @param destinationNodeId The target location of the slot data.
    * @param masters The list of nodes in the cluster that will be assigned hash slots.
    * @param clusterConnections The list of connections to nodes in the cluster.
    * @param executionContext The thread dispatcher context.
    * @return Future indicating success.
    */
  protected def migrateSlot(slot: Int, sourceNodeId: String, destinationNodeId: String, destinationURI: RedisURI,
                          masters: mutable.Buffer[RedisClusterNode],
                          clusterConnections: util.HashMap[String,Future[SaladClusterAPI[CodecType,CodecType]]])
                         (implicit saladAPI: Salad, executionContext: ExecutionContext)
  : Future[Unit] = {
    destinationNodeId match {
      case `sourceNodeId` =>
        // Don't migrate if the source and destination are the same.
        Future(Unit)
      case _ =>
        for {
          sourceConnection <- clusterConnections.get(sourceNodeId)
          destinationConnection <- clusterConnections.get(destinationNodeId)
        } yield {
          // Sequentially execute the steps outline in:
          // https://redis.io/commands/cluster-setslot#redis-cluster-live-resharding-explained
          import com.github.kliewkliew.salad.serde.ByteArraySerdes._
          val migrationResult =
            for {
              _ <- destinationConnection.clusterSetSlotStable(slot).recover { case _ => Unit }
              _ <- sourceConnection.clusterSetSlotStable(slot).recover { case _ => Unit }
              _ <- destinationConnection.clusterSetSlotImporting(slot, sourceNodeId)
              _ <- sourceConnection.clusterSetSlotMigrating(slot, destinationNodeId)
              keyCount <- sourceConnection.clusterCountKeysInSlot(slot)
              keyList <- sourceConnection.clusterGetKeysInSlot[CodecType](slot, keyCount.toInt)
              _ <- sourceConnection.migrate[CodecType](destinationURI, keyList.toList)
              _ <- sourceConnection.clusterSetSlotNode(slot, destinationNodeId)
              finalResult <- destinationConnection.clusterSetSlotNode(slot, destinationNodeId)
            } yield {
              finalResult
            }
          migrationResult.onSuccess { case _ => logger.trace(s"Migrated data of slot $slot from $sourceNodeId to: $destinationNodeId at $destinationURI") }
          migrationResult.onFailure { case e => logger.debug(s"Failed to migrate data of slot $slot from $sourceNodeId to: $destinationNodeId at $destinationURI", e)}
          // Undocumented but necessary final steps found in http://download.redis.io/redis-stable/src/redis-trib.rb
          // `recover` to perform these steps even if the previous steps failed, but don't perform these steps until the previous steps did attempt execution.
          val finalMigrationResult = migrationResult.recover { case _ => Unit }
            .flatMap(_ => notifySlotAssignment(slot, masters)).recover { case _ => Unit }
            .flatMap(_ => sourceConnection.clusterDelSlot(slot)).recover { case _ => Unit }
            .flatMap(_ => destinationConnection.clusterAddSlot(slot))
          finalMigrationResult.onSuccess { case _ => logger.trace(s"Updated slot table for slot $slot") }
          finalMigrationResult.onFailure { case e => logger.debug(s"Failed to update slot table for slot $slot", e)}
          finalMigrationResult.map(x => x)
        }
    }
  }

  /**
    * Notify all master nodes of a slot assignment so that they will immediately be able to redirect clients.
    *
    * @param masters The list of nodes in the cluster that will be assigned hash slots.
    * @param executionContext The thread dispatcher context.
    * @return Future indicating success.
    */
  protected def notifySlotAssignment(slot: Int, masters: mutable.Buffer[RedisClusterNode])
                                  (implicit saladAPI: Salad, executionContext: ExecutionContext)
  : Future[Unit] = {
    val getMasterConnections = masters.map(master => getConnection(master.getNodeId))
    Future.sequence(getMasterConnections).flatMap { masterConnections =>
      val notifyResults = masterConnections.map(_.clusterSetSlotNode(slot, slotNode(slot, masters)))
      Future.sequence(notifyResults).map(x => x)
    }
  }

  /**
    * Store the n poorest masters.
    * Implemented on scala.mutable.PriorityQueue.
    *
    * @param n
    */
  sealed case class MaxNHeapMasterSlaveCount(n: Int) {
    private type MSTuple = (String, AtomicInteger)
    private object MSOrdering extends Ordering[MSTuple] {
      def compare(a: MSTuple, b: MSTuple) = a._2.intValue compare b._2.intValue
    }
    implicit private val ordering = MSOrdering
    val underlying = new mutable.PriorityQueue[MSTuple]

    /**
      * O(1) if the entry is not a candidate for the being one of the poorest n masters.
      * O(log(n)) if the entry is a candidate.
      *
      * @param entry The candidate master-slavecount tuple.
      */
    def offer(entry: MSTuple) =
      if (n > underlying.length) {
        underlying.enqueue(entry)
      }
      else if (entry._2.intValue < underlying.head._2.intValue) {
        underlying.dequeue()
        underlying.enqueue(entry)
      }
  }
}

class CornucopiaKafkaSource extends CornucopiaGraph {
  import Config.Consumer.materializer

  implicit val newSaladAPIimpl: Salad = newSaladAPI

  private type KafkaRecord = ConsumerRecord[String, String]

  private def extractKeyValue = Flow[KafkaRecord]
    .map[KeyValue](record => KeyValue(record.key, record.value))

  def run = RunnableGraph.fromGraph(GraphDSL.create() { implicit builder =>
    import scala.concurrent.ExecutionContext.Implicits.global
    import GraphDSL.Implicits._

    val in = Config.Consumer.cornucopiaKafkaSource
    val out = Sink.ignore

    val mergeFeedback = builder.add(MergePreferred[KeyValue](2))

    val partition = builder.add(Partition[KeyValue](
      5, kv => partitionEvents(kv.key)))

    val kv = builder.add(extractKeyValue)

    val partitionRm = builder.add(Partition[KeyValue](
      3, kv => partitionNodeRemoval(kv.key)
    ))

    in                                      ~> kv
    kv                                      ~> mergeFeedback.preferred
    mergeFeedback.out                       ~> partition
    partition.out(ADD_MASTER.ordinal)       ~> streamAddMaster      ~> mergeFeedback.in(0)
    partition.out(ADD_SLAVE.ordinal)        ~> streamAddSlave       ~> out
    partition.out(REMOVE_NODE.ordinal)      ~> streamRemoveNode     ~> partitionRm
    partitionRm.out(REMOVE_MASTER.ordinal)  ~> mergeFeedback.in(1)
    partitionRm.out(REMOVE_SLAVE.ordinal)   ~> streamRemoveSlave    ~> out
    partitionRm.out(UNSUPPORTED.ordinal)    ~> unsupportedOperation ~> out
    partition.out(RESHARD.ordinal)          ~> streamReshard        ~> out
    partition.out(UNSUPPORTED.ordinal)      ~> unsupportedOperation ~> out

    ClosedShape
  }).run()

}

class CornucopiaActorSource(implicit newSaladAPIimpl: Salad) extends CornucopiaGraph {
  import Config.Consumer.materializer
  import com.github.kliewkliew.cornucopia.actors.CornucopiaSource.Task
  import scala.concurrent.ExecutionContext.Implicits.global

  protected type ActorRecord = Task

  // Add a master node to the cluster.
  def streamAddMasterPrime(implicit executionContext: ExecutionContext, newSaladAPIimpl: Connection.Salad) = Flow[KeyValue]
    .map(kv => (kv.value, kv.senderRef))
    .map(t => (RedisURI.create(t._1), t._2) )
    .map(t => (newSaladAPIimpl.canonicalizeURI(t._1), t._2))
    .groupedWithin(1, Config.Cornucopia.batchPeriod)
    .mapAsync(1)(t => {
      val t1 = t.unzip
      val redisURIs = t1._1
      val actorRefs = t1._2
      addNodesToCluster(redisURIs) flatMap { uris =>
        waitForTopologyRefresh2[Seq[RedisURI], Seq[Option[ActorRef]]](uris, actorRefs)
      }
    })
    .map{ case (_, actorRef) =>
      val ref = actorRef.head
      KeyValue(RESHARD.key, "", ref)
    }

  override protected def streamReshard(implicit executionContext: ExecutionContext) = Flow[KeyValue]
    .map(record => Seq( record.senderRef ))
    .conflate((seq1, seq2) => seq1 ++ seq2 )
    .throttle(1, Config.Cornucopia.minReshardWait, 1, ThrottleMode.Shaping)
    .mapAsync(1)(reshardClusterPrime)
    .mapAsync(1)(waitForTopologyRefresh[Unit])
    .mapAsync(1)(_ => logTopology)
    .map(_ => KeyValue("", ""))

  private def reshardClusterPrime(senders: Seq[Option[ActorRef]]): Future[Unit] = {

    def reshard(ref: ActorRef): Future[Unit] = {
      reshardCluster(Seq()) map { _: Unit =>
        ref ! Right("OK")
      } recover {
        case ex: Throwable => ref ! Left(s"ERROR: ${ex.toString}")
      }
    }

    val flattened = senders.flatten

    if (flattened.size == 0) Future(Unit)
    else Future.reduce(senders.flatten.map(reshard))((_, _) => Unit)
  }

  protected def extractKeyValue = Flow[ActorRecord]
    .map[KeyValue](record => KeyValue(record.operation, record.redisNodeIp, record.ref))

  protected val processTask = Flow.fromGraph(GraphDSL.create() { implicit builder =>
    import GraphDSL.Implicits._

    val taskSource = builder.add(Flow[Task])

    val mergeFeedback = builder.add(MergePreferred[KeyValue](2))

    val partition = builder.add(Partition[KeyValue](
      5, kv => partitionEvents(kv.key)))

    val kv = builder.add(extractKeyValue)

    val partitionRm = builder.add(Partition[KeyValue](
      3, kv => partitionNodeRemoval(kv.key)
    ))

    val broadcastSender = builder.add(Broadcast[KeyValue](2))

    val senderMerge = builder.add(Merge[KeyValue](2))

    val fanIn = builder.add(Merge[KeyValue](5))

    taskSource.out                          ~> kv                   ~> broadcastSender
    broadcastSender                         ~> mergeFeedback.preferred
    broadcastSender                         ~> senderMerge
    mergeFeedback.out                       ~> partition
    partition.out(ADD_MASTER.ordinal)       ~> streamAddMasterPrime ~> mergeFeedback.in(0)
    partition.out(ADD_SLAVE.ordinal)        ~> streamAddSlave       ~> fanIn
    partition.out(REMOVE_NODE.ordinal)      ~> streamRemoveNode     ~> partitionRm
    partitionRm.out(REMOVE_MASTER.ordinal)  ~> mergeFeedback.in(1)
    partitionRm.out(REMOVE_SLAVE.ordinal)   ~> streamRemoveSlave    ~> fanIn
    partitionRm.out(UNSUPPORTED.ordinal)    ~> unsupportedOperation ~> fanIn
    partition.out(RESHARD.ordinal)          ~> streamReshard        ~> fanIn
    partition.out(UNSUPPORTED.ordinal)      ~> unsupportedOperation ~> fanIn

    fanIn ~> senderMerge

    FlowShape(taskSource.in, senderMerge.out)
  })

  protected val cornucopiaSource = Config.Consumer.cornucopiaActorSource

  def ref: ActorRef = processTask
    .to(Sink.ignore)
    .runWith(cornucopiaSource)

}
