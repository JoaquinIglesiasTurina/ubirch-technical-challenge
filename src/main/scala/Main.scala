import org.apache.kafka.clients.producer.{ProducerRecord, RecordMetadata}
import zio._
import zio.Console.printLine
import zio.Schedule.WithState
import zio.kafka.consumer.{Consumer, ConsumerSettings, Subscription}
import zio.kafka.producer.{Producer, ProducerSettings}
import zio.kafka.serde.Serde
import zio.stream._

import java.util.UUID

object KafkaClient {
  private val producerSettings = ProducerSettings(List("localhost:9092"))
  private val producerResource = Producer.make(producerSettings)
  val producer: ZLayer[Any, Throwable, Producer] = ZLayer.scoped(producerResource)

  private val consumerSettings = ConsumerSettings(List("localhost:9092"))
    .withGroupId("updates-consumer")
  private val managedConsumer = Consumer.make(consumerSettings) //efectful resources
  val consumer: ZLayer[Any, Throwable, Consumer] = ZLayer.scoped(managedConsumer) // effectful DI
}

object MintEvent {
  private def produceMintRecord(uuid: UUID): RIO[Producer, RecordMetadata] = {
    val record = new ProducerRecord[UUID, String]("mints", uuid, "minted")
    Producer.produce[Any, UUID, String](record, Serde.uuid, Serde.string)
  }
  val getNewMint: ZIO[Producer, Throwable, UUID] = for {
    uuid <- Random.nextUUID
    _ <- printLine(s"minted item with uuid: $uuid")
    _ <- produceMintRecord(uuid)
  } yield uuid
  val mintSchedule: WithState[Long, Any, Any, Long] = Schedule.spaced(3.seconds)
}

object FindUsers {
  private val users = List.fill(15)(java.util.UUID.randomUUID())

  private def produceUserCallback(userID: UUID, itemID: UUID): RIO[Producer, RecordMetadata] = {
    val record = new ProducerRecord[UUID, UUID]("usercallback", userID, itemID)
    Producer.produce[Any, UUID, UUID](record, Serde.uuid, Serde.uuid)
  }

  private def getUsersOfMinted(key: UUID, value: String): ZIO[Producer, Throwable, Unit] = {
    ZStream.fromIterableZIO {
      for {
        u0 <- Random.shuffle(users)
      } yield u0.take(3)
    }.tap { user => produceUserCallback(user, key) }
      .tap { user => printLine(s"user with id $user is interested on item $key") }
      .run(ZSink.drain)
  }

  private val mintsConsumer = Consumer.subscribeAnd(Subscription.topics("mints"))
    .plainStream(Serde.uuid, Serde.string)
  private val mintStream = mintsConsumer.map(cr => (cr.key, cr.value, cr.offset))
    .tap { case (key, value, _) => getUsersOfMinted(key, value) }
    .map(_._3).aggregateAsync(Consumer.offsetBatches)
  val mintStreamEffect: ZIO[Producer with Any with Consumer, Throwable, Unit] =
    mintStream.run(ZSink.foreach(record => record.commit))
}

object CallbackNotification {
  private val callbacksConsumer = Consumer.subscribeAnd(Subscription.topics("usercallback"))
    .plainStream(Serde.uuid, Serde.uuid)

  private val callbacksStream = callbacksConsumer.map(cr => (cr.key, cr.value, cr.offset))
    .tap { case (key, value, _) =>
      printLine(s"sending email notification to user with id $key about item with id $value")
    }
    .map(_._3).aggregateAsync(Consumer.offsetBatches)
  val callbacksEffect: ZIO[Any with Consumer, Throwable, Unit] =
    callbacksStream.run(ZSink.foreach(record => record.commit))
}

object Main extends ZIOAppDefault {
  override def run: ZIO[Environment with ZIOAppArgs with Scope, Any, Any] = {
    for {
      _ <- CallbackNotification.callbacksEffect.provideSomeLayer(KafkaClient.consumer).fork
      _ <- FindUsers.mintStreamEffect.provideSomeLayer(KafkaClient.consumer ++ KafkaClient.producer).fork
      _ <- MintEvent.getNewMint.provideSomeLayer(KafkaClient.producer) repeat MintEvent.mintSchedule
    } yield ()
  }
}