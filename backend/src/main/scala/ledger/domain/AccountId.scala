package ledger.domain

import java.util.UUID
import cats.effect.Sync

opaque type AccountId = UUID

object AccountId:
  def random[F[_]: Sync]: F[AccountId] = Sync[F].delay(UUID.randomUUID())

  def fromString(s: String): Either[String, AccountId] =
    scala.util.Try(UUID.fromString(s)).toEither.left.map(_ => s"Invalid account id: $s")

  extension (id: AccountId)
    def asString: String = id.toString
