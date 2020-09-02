package com.evolutiongaming.kafka.flow.journal

import cats.implicits._
import com.evolutiongaming.catshelper.Log
import com.evolutiongaming.kafka.flow.FoldOption
import com.evolutiongaming.kafka.flow.snapshot.KafkaSnapshot
import com.evolutiongaming.kafka.journal.ActionHeader
import com.evolutiongaming.kafka.journal.ConsRecord
import com.evolutiongaming.kafka.journal.HeaderMetadata
import com.evolutiongaming.kafka.journal.JsonCodec
import com.evolutiongaming.kafka.journal.PayloadType
import com.evolutiongaming.kafka.journal.SeqNr
import com.evolutiongaming.kafka.journal.SeqRange
import com.evolutiongaming.kafka.journal.ToBytes
import com.evolutiongaming.skafka.Header
import com.evolutiongaming.skafka.Offset
import com.evolutiongaming.skafka.TimestampAndType
import com.evolutiongaming.skafka.TimestampType
import com.evolutiongaming.skafka.TopicPartition
import com.evolutiongaming.skafka.consumer.WithSize
import java.time.Instant
import munit.FunSuite
import scala.util.Success
import scala.util.Try
import scodec.bits.ByteVector

import JournalFoldSpec._

class JournalFoldSpec extends FunSuite {

  test("JournalFold updates KafkaSnapshot when there is no state") {
    val f = new ConstFixture
    val state0 = None
    val record = f.record(Offset.unsafe(1), SeqNr.unsafe(100))

    val state1 = record flatMap { record =>
      f.fold(state0, record)
    }

    assert(state1 == Success(Some(KafkaSnapshot(offset = Offset.unsafe(1), value = SeqNr.unsafe(100)))))
  }

  test("JournalFold updates KafkaSnapshot when there is an existing state") {
    val f = new ConstFixture
    val state0 = Some(KafkaSnapshot(offset = Offset.unsafe(1), value = SeqNr.unsafe(100)))
    val record = f.record(Offset.unsafe(2), SeqNr.unsafe(101))

    val state1 = record flatMap { record =>
      f.fold(state0, record)
    }
    assert(state1 == Success(Some(KafkaSnapshot(offset = Offset.unsafe(2), value = SeqNr.unsafe(101)))))
  }

  test("JournalFold ignores duplicate offset") {
    val f = new ConstFixture
    val state0 = None
    val record = f.record(Offset.unsafe(1), SeqNr.unsafe(100))

    val state1 = record flatMap { record =>
      f.fold(state0, record)
    }
    assert(state1 == Success(Some(KafkaSnapshot(offset = Offset.unsafe(1), value = SeqNr.unsafe(100)))))

    val state2 = state1 product record flatMap { case (state1, record) =>
      f.fold(state1, record)
    }
    assert(state2 == state1)
  }

  test("JournalFold ignores duplicate sequence number") {
    val f = new ConstFixture
    val state0 = None
    val record0 = f.record(Offset.unsafe(1), SeqNr.unsafe(100))
    val record1 = f.record(Offset.unsafe(2), SeqNr.unsafe(100))

    val state1 = record0 flatMap { record0 =>
      f.fold(state0, record0)
    }
    assert(state1 == Success(Some(KafkaSnapshot(offset = Offset.unsafe(1), value = SeqNr.unsafe(100)))))

    val state2 = state1 product record1 flatMap { case (state1, record1) =>
      f.fold(state1, record1)
    }
    assert(state2 == state1)
  }

}
object JournalFoldSpec {

  class ConstFixture {

    def record(offset: Offset, seqNr: SeqNr) = for {
      header <- ToBytes[Try, ActionHeader].apply(
        ActionHeader.Append(
          range = SeqRange(seqNr),
          origin = None,
          payloadType = PayloadType.Json,
          metadata = HeaderMetadata.empty
        )
      )
      record = ConsRecord(
        topicPartition = TopicPartition.empty,
        offset = offset,
        timestampAndType = Some(TimestampAndType(
          timestamp = Instant.parse("2020-01-02T03:04:05.000Z"),
          timestampType = TimestampType.Append
        )),
        key = Some(WithSize("id")),
        value = Some(WithSize(ByteVector.empty)),
        headers = List(Header(ActionHeader.key, header.toArray))
      )
    } yield record

    val fold = JournalFold.explicitSeqNr[Try, SeqNr](
      FoldOption.of { (_, record) =>
        JournalParser[Try].toSeqRange(record) map { seqRange =>
          seqRange map (_.from)
        }
      }
    )(identity)

  }

  implicit val jsonCodec: JsonCodec[Try] = JsonCodec.default
  implicit val journalParser: JournalParser[Try] = JournalParser.of
  implicit val log: Log[Try] = Log.empty

}