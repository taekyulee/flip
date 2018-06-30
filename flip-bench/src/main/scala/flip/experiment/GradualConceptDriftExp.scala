package flip.experiment

import flip.implicits._
import flip.experiment.ops.ExpOutOps
import flip.rand.IRng

/**
  * A experiment for gradual concept drift.
  * https://edouardfouche.com/img/concept-drift/conceptdrift.png
  * */
object GradualConceptDriftExp {

  def main(args: Array[String]): Unit = {
    val expName = "gradual-cd-normal"
    val dataNo = 1000
    val draftStart = 300
    val draftStartingPoint = 0.0
    val velocity = 0.01

    def center(idx: Int) =
      if (draftStart > idx) draftStartingPoint
      else draftStartingPoint + velocity * (idx - draftStart)
    val rng: IRng = IRng(0)
    def underlying(idx: Int, rng: IRng = rng): NumericDist[Double] =
      if (draftStart > idx) NumericDist.normal(draftStartingPoint, 1.0, rng)
      else NumericDist.normal(center(idx), 1.0, rng)
    val datas: List[Double] = {
      var tempRng = rng
      (0 to dataNo).toList.map(idx => {
        val (utdDist, sample) = underlying(idx, tempRng).sample
        tempRng = utdDist.asInstanceOf[NumericDist[Double]].rng
        sample
      })
    }

    implicit val conf: SketchConf = SketchConf(
      decayFactor = 1,
      cmapStart = Some(-20.0),
      cmapEnd = Some(20.0)
    )
    val sketch0 = Sketch.empty[Double]
    val sketchTraces = sketch0 :: sketch0.updateTrace(datas)
    val idxSketches = sketchTraces.indices.zip(sketchTraces).toList.filter { case (idx, _) => idx % 10 == 0 }
    val idxPdf = idxSketches.map { case (idx, skt) => (idx, skt.barPlot.csv) }
    val idxCdf = idxSketches.map { case (idx, sketch) => (idx, sketch.cdfSampling.csv) }
    val idxDel = idxSketches.map { case (idx, sketch) => (idx, Delta(underlying(idx), sketch).csv) }
    val idxKld = idxSketches.map { case (idx, sketch) => (idx, KLD(underlying(idx), sketch)) }
    val idxCos = idxSketches.map { case (idx, sketch) => (idx, Cosine(underlying(idx), sketch)) }
    val idxEuc = idxSketches.map { case (idx, sketch) => (idx, Euclidean(underlying(idx), sketch)) }
    val idxED = idxSketches.map { case (idx, sketch) => (idx, ED(underlying(idx), sketch)) }
    val idxMedian = idxSketches.map { case (idx, sketch) => (idx, sketch.median) }

    // out
    ExpOutOps.clear(expName)
    ExpOutOps.writeStrs(expName, "pdf", idxPdf)
    ExpOutOps.writeStrs(expName, "cdf", idxCdf)
    ExpOutOps.writeStrs(expName, "delta", idxDel)
    ExpOutOps.writeStr(expName, "kld", idxKld.map { case (idx, kld) => s"$idx, $kld" }.mkString("\n"))
    ExpOutOps.writeStr(expName, "cosine", idxCos.map { case (idx, cos) => s"$idx, $cos" }.mkString("\n"))
    ExpOutOps.writeStr(expName, "euclidean", idxEuc.map { case (idx, euc) => s"$idx, $euc" }.mkString("\n"))
    ExpOutOps.writeStr(expName, "ed", idxED.map { case (idx, ed) => s"$idx, $ed" }.mkString("\n"))
    ExpOutOps.writeStr(
      expName,
      "median",
      idxMedian.map { case (idx, sktMed) => s"$idx, ${center(idx)}, $sktMed" }.mkString("\n"))

    // console print
    val avgSize = 10
    val avgKld = idxKld.takeRight(avgSize).map(_._2).sum / avgSize
    val avgCos = idxCos.takeRight(avgSize).map(_._2).sum / avgSize
    val avgEuc = idxEuc.takeRight(avgSize).map(_._2).sum / avgSize

    val str = s"Similarity for gradual concept-drifted data stream with velocity $velocity: \n" +
      s" KLD: $avgKld \n" +
      s" Cosine: $avgCos \n" +
      s" Euclidean: $avgEuc"
    println(str)
  }

}
