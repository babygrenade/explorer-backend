package org.ergoplatform.explorer.clients

import cats.data.NonEmptyList
import cats.effect.Sync
import cats.syntax.functor._
import cats.{ApplicativeError, Monad}
import fs2.Stream
import io.chrisdavenport.log4cats.Logger
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import io.circe.Decoder
import jawnfs2._
import org.ergoplatform.{ErgoLikeTransaction, JsonCodecs}
import org.ergoplatform.explorer.Err.ProcessingErr.TransactionDecodingFailed
import org.ergoplatform.explorer.protocol.models.{ApiFullBlock, ApiNodeInfo, ApiTransaction}
import org.ergoplatform.explorer.{CRaise, Id, UrlString}
import org.http4s.client.Client
import org.http4s.{Method, Request, Uri}
import io.circe.jawn.CirceSupportParser.facade
import org.http4s.circe.CirceEntityDecoder._
import org.http4s.circe.CirceEntityEncoder._
import tofu.syntax.raise._

/** A service providing an access to the Ergo network.
  */
trait ErgoNetworkClient[F[_], S[_[_], _]] {

  /** Get height of the best block.
    */
  def getBestHeight: F[Int]

  /** Get block ids at the given `height`.
    */
  def getBlockIdsAtHeight(height: Int): F[List[Id]]

  /** Get full block by its `id`.
    */
  def getFullBlockById(id: Id): F[Option[ApiFullBlock]]

  /** Get unconfirmed transactions from UTX pool.
    */
  def getUnconfirmedTransactions: S[F, ApiTransaction]

  /** Submit a transaction to the network.
    */
  def submitTransaction(tx: ErgoLikeTransaction): F[String]
}

object ErgoNetworkClient {

  def apply[F[_]: Sync](
    client: Client[F],
    masterNodesAddresses: NonEmptyList[UrlString]
  ): F[ErgoNetworkClient[F, Stream]] =
    Slf4jLogger
      .create[F]
      .map(new Live[F](client, _, masterNodesAddresses))

  final private class Live[F[_]: Sync: CRaise[*[_], TransactionDecodingFailed]](
    client: Client[F],
    logger: Logger[F],
    masterNodesAddresses: NonEmptyList[UrlString]
  ) extends ErgoNetworkClient[F, Stream]
    with JsonCodecs {

    def getBestHeight: F[Int] =
      retrying { url =>
        client
          .expect[ApiNodeInfo](
            makeGetRequest(s"$url/info")
          )
          .map(_.fullHeight)
      }

    def getBlockIdsAtHeight(height: Int): F[List[Id]] =
      retrying { url =>
        client.expect[List[Id]](
          makeGetRequest(s"$url/blocks/at/$height")
        )
      }

    def getFullBlockById(id: Id): F[Option[ApiFullBlock]] =
      retrying { url =>
        client.expectOption[ApiFullBlock](
          makeGetRequest(s"$url/blocks/$id")
        )
      }

    def getUnconfirmedTransactions: Stream[F, ApiTransaction] =
      retrying[Stream[F, *], ApiTransaction] { url =>
        client
          .stream(makeGetRequest(s"$url/transactions/unconfirmed"))
          .flatMap(_.body.chunks.parseJsonStream)
          .flatMap { json =>
            implicitly[Decoder[ApiTransaction]]
              .decodeJson(json)
              .fold(
                _ => Stream.eval(TransactionDecodingFailed(json.noSpaces).raise),
                Stream.emit
              )
          }
      }

    def submitTransaction(tx: ErgoLikeTransaction): F[String] =
      retrying { url =>
        client.expect[String](
          Request[F](
            Method.POST,
            Uri.unsafeFromString(s"$url/transactions")
          ).withEntity(tx)
        )
      }

    private def retrying[M[_]: Monad, A](
      f: UrlString => M[A]
    )(implicit G: ApplicativeError[M, Throwable]): M[A] = {
      def attempt(urls: List[UrlString])(i: Int): M[A] =
        urls match {
          case hd :: tl =>
            G.handleErrorWith(f(hd)) { e =>
              println(e) // todo:
              attempt(tl)(i + 1)
            }
          case Nil =>
            G.raiseError(new Exception(s"Gave up after $i attempts"))
        }
      attempt(masterNodesAddresses.toList)(0)
    }

    private def makeGetRequest(uri: String) =
      Request[F](Method.GET, Uri.unsafeFromString(uri))
  }
}
