package org.ergoplatform.explorer.protocol

import cats.{Applicative, FlatMap, Monad}
import cats.syntax.flatMap._
import eu.timepit.refined.refineMV
import eu.timepit.refined.string.HexStringSpec
import org.ergoplatform.explorer.Err.RefinementFailed
import org.ergoplatform.explorer.Err.RequestProcessingErr.DexErr.{
  DexBuyOrderAttributesFailed,
  DexSellOrderAttributesFailed
}
import org.ergoplatform.explorer.Err.RequestProcessingErr.ContractParsingErr
import org.ergoplatform.explorer.protocol.utils.{bytesToErgoTree, hexStringBase16ToBytes}
import org.ergoplatform.explorer.{HexString, TokenId}
import scorex.util.encode.Base16
import sigmastate.Values.{ByteArrayConstant, ErgoTree}
import sigmastate.{SLong, Values}
import tofu.Raise.ContravariantRaise
import tofu.syntax.raise._

object dex {

  // assuming buyer contract from http://github.com/ScorexFoundation/sigmastate-interpreter/blob/42e55cbfd093252b8005e4607970764dd6610cbe/contract-verification/src/main/scala/sigmastate/verification/contract/AssetsAtomicExchange.scala#L33-L45
  private val SellContractTokenPriceIndexInConstants = 5

  /** template for the buyer contract as of http://github.com/ScorexFoundation/sigmastate-interpreter/blob/42e55cbfd093252b8005e4607970764dd6610cbe/contract-verification/src/main/scala/sigmastate/verification/contract/AssetsAtomicExchange.scala#L33-L45
    */
  val sellContractTemplate: HexString = HexString(
    refineMV[HexStringSpec](
      "eb027300d1eded91b1a57301e6c6b2a5730200040ed801d60193e4c6b2a5730300040ec5a7eded92c1b2a57304007305720193c2b2a5730600d07307"
    )
  )

  // assuming seller contract from http://github.com/ScorexFoundation/sigmastate-interpreter/blob/42e55cbfd093252b8005e4607970764dd6610cbe/contract-verification/src/main/scala/sigmastate/verification/contract/AssetsAtomicExchange.scala#L33-L45
  val BuyContractTokenIdIndexInConstants     = 6
  val BuyContractTokenAmountIndexInConstants = 8

  /** template for the buyer contract as of http://github.com/ScorexFoundation/sigmastate-interpreter/blob/42e55cbfd093252b8005e4607970764dd6610cbe/contract-verification/src/main/scala/sigmastate/verification/contract/AssetsAtomicExchange.scala#L12-L32
    */
  val buyContractTemplate: HexString = HexString(
    refineMV[HexStringSpec](
      "eb027300d1eded91b1a57301e6c6b2a5730200040ed803d601e4c6b2a5730300020c4d0ed602eded91b172017304938cb27201730500017306928cb27201730700027308d60393e4c6b2a5730900040ec5a7eded720293c2b2a5730a00d0730b7203"
    )
  )

  @inline def getTokenPriceFromSellContractTree[
    F[_]: ContravariantRaise[*[_], DexSellOrderAttributesFailed]: Applicative
  ](tree: ErgoTree): F[Long] =
    tree.constants
      .lift(SellContractTokenPriceIndexInConstants)
      .collect {
        case Values.ConstantNode(value, SLong) =>
          value.asInstanceOf[Long]
      }
      .orRaise(
        DexSellOrderAttributesFailed(
          s"Cannot find token price in constants($SellContractTokenPriceIndexInConstants) in sell order ergo tree $tree"
        )
      )

  def getTokenPriceFromSellOrderTree[
    F[_]: ContravariantRaise[*[_], DexSellOrderAttributesFailed]: ContravariantRaise[*[_], ContractParsingErr]: FlatMap: Applicative
  ](ergoTreeStr: HexString): F[Long] =
    hexStringBase16ToBytes[F](ergoTreeStr)
      .flatMap(bytes =>
        bytesToErgoTree[F](bytes)
          .flatMap(getTokenPriceFromSellContractTree[F])
      )

  @inline def getTokenInfoFromBuyContractTree[
    F[_]: ContravariantRaise[*[_], DexBuyOrderAttributesFailed]: ContravariantRaise[*[_], RefinementFailed]: Monad
  ](tree: ErgoTree): F[(TokenId, Long)] =
    tree.constants
      .lift(BuyContractTokenIdIndexInConstants)
      .collect {
        case ByteArrayConstant(coll) =>
          TokenId.fromString(Base16.encode(coll.toArray))
      }
      .orRaise(
        DexBuyOrderAttributesFailed(
          s"Cannot find tokenId in the buy order ergo tree $tree"
        )
      )
      .flatten
      .flatMap(tokenId =>
        tree.constants
          .lift(BuyContractTokenAmountIndexInConstants)
          .collect {
            case Values.ConstantNode(value, SLong) =>
              value.asInstanceOf[Long]
          }
          .map((tokenId, _))
          .orRaise(
            DexBuyOrderAttributesFailed(
              s"Cannot find token amount in the buy order ergo tree $tree"
            )
          )
      )

  def getTokenInfoFromBuyOrderTree[
    F[_]: ContravariantRaise[*[_], DexBuyOrderAttributesFailed]
        : ContravariantRaise[*[_], ContractParsingErr]
        : ContravariantRaise[*[_], RefinementFailed]
        : Monad
  ](ergoTreeStr: HexString): F[(TokenId, Long)] =
    hexStringBase16ToBytes[F](ergoTreeStr)
      .flatMap(bytes =>
        bytesToErgoTree[F](bytes)
          .flatMap(getTokenInfoFromBuyContractTree[F])
      )
}
