package sketch.scope.pdf.monad

import sketch.scope.pdf.{Dist, SamplingDist}

import scala.language.higherKinds

/**
  * Licensed by Probe Technology, Inc.
  */
trait SamplingDistMonad[SD1[_]<:SamplingDist[_], D[_]<:Dist[_], SD2[_]<:SamplingDist[_]] extends DistMonad[SD1, D, SD2]

object SamplingDistMonad {

  def apply(): SamplingDistMonad[SamplingDist, Dist, SamplingDist] = ???

}