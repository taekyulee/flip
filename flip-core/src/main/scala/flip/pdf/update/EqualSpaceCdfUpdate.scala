package flip.pdf.update

import flip.cmap.Cmap
import flip.pdf._
import flip.plot._
import flip.plot.syntax._
import flip.range.RangeP

trait EqualSpaceCdfUpdate {

  def updateCmapForSketch(sketch: Sketch[_], ps: List[(Prim, Count)]): Option[Cmap] = for {
    sketchSamples <- flip.time(sketch.sampling, "sampling", false) // 3e7
    mixingRatio = sketch.conf.mixingRatio
    window = sketch.conf.dataKernelWindow
    corr = sketch.conf.boundaryCorrection
    cmapSize = sketch.conf.cmap.size
  } yield updateCmap(sketchSamples, ps, mixingRatio, window, corr, cmapSize)

  def updateCmap(sketchSamples: DensityPlot, ps: List[(Prim, Count)],
                 mixingRatio: Double, window: Double, corr: Double, cmapSize: Int): Cmap = {
    val mergedPlot = flip.time(if(ps.nonEmpty) {
      val c1 = 1 / (mixingRatio + 1)
      val c2 = mixingRatio / (mixingRatio + 1)
      (c1, sketchSamples) + (c2, DensityPlot.squareKernel(ps, window))
    } else sketchSamples, "mergedPlot", false) // 2e7 vs 2e4

    flip.time(cmapForEqualSpaceCumCorr(mergedPlot, corr, cmapSize), "cmapForEqualSpaceCumCorr", false) // 7e7 vs 2e7
  }

  /**
    * @param corr boundary marginal ratio for the separation unit.
    *             If corr=1, cmapForEqualSpaceCumCorr is identical to standard cmapForEqualSpaceCum.
    *             If corr=0, cmap has no margin.
    * */
  def cmapForEqualSpaceCumCorr(plot: DensityPlot, corr: Double, cmapSize: Int): Cmap = {
    lazy val invCdf = flip.time(plot.inverseCumulative, "invCdf", false) // 2e6

    val cdfDivider = if(cmapSize < 2) {
      Nil
    } else if(cmapSize == 2) {
      0.5 :: Nil
    } else {
      val maxAccumulative = invCdf.domain.map(_.end).getOrElse(1.0)
      val unit = maxAccumulative / (cmapSize.toDouble - 2 + 2 * corr)

      flip.time((1 until cmapSize).toList
        .map(i => unit * corr + unit * (i - 1)), "cdfDivider", false) // 2e5
    }

    val pDivider = flip.time(cdfDivider.map(a => invCdf.interpolation(a)), "pDivider", false) // 3e6

    flip.time(Cmap.divider(pDivider), "Cmap.divider", false) // 3e6
  }

  def smoothingPsForEqualSpaceCumulative(ps: List[(Prim, Count)]): DensityPlot = {
    val sorted = ps.sortBy(_._1)
    val sliding: List[List[(Prim, Count)]] = sorted.sliding(2).toList
    val headAppendingO: Option[(Prim, Count)] = sliding.headOption.flatMap {
      case (p1, _) :: (p2, _) :: Nil => Some((p1 - (p2 - p1), 0d))
      case _ => None
    }
    val lastAppendingO: Option[(Prim, Count)] = sliding.lastOption.flatMap {
      case (p1, _) :: (p2, _) :: Nil => Some((p2 + (p2 - p1), 0d))
      case _ => None
    }

    val records = (headAppendingO.toList ::: sorted ::: lastAppendingO.toList)
      .sliding(2).toList
      .flatMap {
        case (p1, count1) :: (p2, count2) :: Nil if !p1.isInfinity && !p2.isInfinity =>
          val range = RangeP(p1, p2)
          if(!range.isPoint) Some((range, (count1 + count2) / (2 * range.length).toDouble)) else None
        case _ => None
      }

    DensityPlot.disjoint(records)
  }

}

object EqualSpaceCdfUpdate extends EqualSpaceCdfUpdate