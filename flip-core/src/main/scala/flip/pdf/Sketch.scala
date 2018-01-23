package flip.pdf

import flip._
import flip.cmap.Cmap
import flip.conf.{AdaptiveSketchConf, PeriodicSketchConf, SketchConf}
import flip.hcounter.HCounter
import flip.measure.Measure
import flip.plot.DensityPlot
import flip.range.{RangeM, RangeP}

import scala.language.higherKinds
import scala.util.Try
import cats.implicits._

import scala.collection.mutable.ListBuffer

/**
  * Sketch provides
  */
trait Sketch[A] extends DataBinningDist[A] {

  /**
    * Internal structure list of Sketch. Order: young -> old
    * */
  def structures: Structures

}

trait SketchPropOps[S[_]<:Sketch[_], C<:SketchConf]
  extends DataBinningDistOps[S, C]
    with SketchPropLaws[S, C] {

  // create ops

  def sample[A](dist: S[A]): (S[A], A)

  // read ops

  /**
    * Get the number of effective elements be memorized.
    * */
  def count[A](sketch: S[A], from: A, to: A, conf: C): Option[Count]

  def sum(sketch: S[_], conf: C): Count

  // update ops

  def modifyStructure[A](sketch: S[A], f: Structures => Option[Structures]): Option[S[A]]

  def narrowUpdate[A](sketch: S[A], as: List[(A, Count)], conf: C): Option[S[A]]

  def deepUpdate[A](sketch: S[A], as: List[(A, Count)], conf: C): Option[(S[A], Option[Structure])]

  //  def clear(sketch: S): S

}

trait SketchPropLaws[S[_]<:Sketch[_], C<:SketchConf] { self: SketchPropOps[S, C] =>

  def flatDensity: Double = (1 / Cmap.max) * (1 / (1 - Cmap.min / Cmap.max))

  def probability[A](sketch: S[A], start: A, end: A, conf: C): Option[Double] = for {
    count <- count(sketch, start, end, conf)
    sum = self.sum(sketch, conf)
    measure = sketch.measure.asInstanceOf[Measure[A]]
    flatProb = flatDensity * RangeM.bare(start, end, measure).roughLength
  } yield if(sum > 0) count / sum else flatProb

  def sampling[A](sketch: S[A], conf: C): Option[DensityPlot] = for {
    cmap <- youngCmap(sketch)
    rangePs = cmap.bin
    measure = sketch.measure.asInstanceOf[Measure[A]]
    rangeMs = rangePs.map(rangeP => rangeP.modifyMeasure(measure))
    sampling <- samplingForRanges(sketch, rangeMs, conf)
  } yield sampling

  def samplingForRanges[A](sketch: S[A], ranges: List[RangeM[A]], conf: C): Option[DensityPlot] = {
    for {
      rangeProbs <- ranges.traverse(range => probability(sketch, range.start, range.end, conf).map(prob => (range, prob)))
      rangeDensities = rangeProbs
        .map { case (rangeM, prob) => (RangeP.forRangeM(rangeM), Try(prob / rangeM.length).toOption) }
        .flatMap { case (range, densityO) => densityO.map(density => (range, density.toDouble)) }
    } yield DensityPlot.disjoint(rangeDensities)
  }

  def fastPdf[A](sketch: S[A], a: A, conf: C): Option[Double] = for {
    cmap <- youngCmap(sketch)
    p = sketch.measure.asInstanceOf[Measure[A]].to(a)
    idx = cmap(p)
    rangePs = cmap.range(idx - 1) :: cmap.range(idx) :: cmap.range(idx + 1) :: Nil
    rangeMs = rangePs.map(rangeP => rangeP.modifyMeasure(sketch.measure.asInstanceOf[Measure[A]]))
    sampling <- samplingForRanges(sketch, rangeMs, conf)
  } yield sampling.interpolation(p)

  def rearrange[A](sketch: S[A], conf: C): Option[S[A]] = deepUpdate(sketch, Nil, conf).map(_._1)

  def cmapNo(sketch: S[_]): Int = sketch.structures.size

  def cmapSize(sketch: S[_]): Int = (for {
    structure <- sketch.structures.headOption
    (cmap, _) = structure
  } yield cmap.size).getOrElse(0)

  def counterNo(sketch: S[_]): Int = (for {
    structure <- sketch.structures.headOption
    (_, hcounter) = structure
  } yield hcounter.depth).getOrElse(0)

  def counterSize(sketch: S[_]): Int = (for {
    structure <- sketch.structures.headOption
    (_, hcounter) = structure
  } yield hcounter.width).getOrElse(0)

  def youngCmap(sketch: S[_]): Option[Cmap] = for {
    structure <- sketch.structures.headOption
    cmap = structure._1
  } yield cmap

  def domain[A](sketch: S[A]): Option[RangeM[A]] = for {
    youngCmap <- youngCmap(sketch)
    head = youngCmap.headRange.start
    last = youngCmap.lastRange.end
    measure = sketch.measure.asInstanceOf[Measure[A]]
  } yield RangeM(measure.from(head), measure.from(last))(measure)

  def conf2Structures(conf: C): Structures = {
    if(conf.cmap.size > conf.counter.size) {
      (Cmap(conf.cmap), HCounter(conf.counter, -1)) :: Nil
    } else {
      (Cmap(conf.cmap), HCounter.emptyUncompressed(conf.cmap.size)) :: Nil
    }
  }

}

object Sketch extends SketchPrimPropOps[Sketch, SketchConf] { self =>

  def apply[A](measure: Measure[A], structure: Structures): Sketch[A] =
    SimpleSketch(measure, structure)

  /**
    * @param measure  measure of Sketch
    * */
  def empty[A](implicit measure: Measure[A], conf: SketchConf): Sketch[A] = conf match {
    case conf: PeriodicSketchConf => PeriodicSketch.empty(measure, conf)
    case _ => SimpleSketch.empty(measure, conf)
  }

  // mapping ops

  def modifyStructure[A](sketch: Sketch[A], f: Structures => Option[Structures]): Option[Sketch[A]] = sketch match {
    case sketch: RecurSketch[_] => RecurSketch.modifyStructure(sketch, f)
    case sketch: AdaptiveSketch[_] => AdaptiveSketch.modifyStructure(sketch, f)
    case _ => SimpleSketch.modifyStructure(sketch, f)
  }

  // syntatic sugars

  def update[A](sketch: Sketch[A], as: List[(A, Count)], conf: SketchConf): Option[Sketch[A]] = (sketch, conf) match {
    case (sketch: RecurSketch[A], _) => RecurSketch.update(sketch, as, conf)
    case (sketch: AdaptiveSketch[A], conf: AdaptiveSketchConf) => AdaptiveSketch.update(sketch, as, conf)
    case (sketch: SimpleSketch[A], _) => SimpleSketch.update(sketch, as, conf)
    case _ => narrowUpdate(sketch, as, conf)
  }

  // overrides

  override def count[A](sketch: Sketch[A], start: A, end: A, conf: SketchConf): Option[Count] = (sketch, conf) match {
    case (sketch: AdaptiveSketch[A], conf: AdaptiveSketchConf) => AdaptiveSketch.count(sketch, start, end, conf)
    case _ => super.count(sketch, start, end, conf)
  }

  override def sum(sketch: Sketch[_], conf: SketchConf): Count = (sketch, conf) match {
    case (sketch: AdaptiveSketch[_], conf: AdaptiveSketchConf) => AdaptiveSketch.sum(sketch, conf)
    case _ => super.sum(sketch, conf)
  }

  override def narrowUpdate[A](sketch: Sketch[A],
                               as: List[(A, Count)],
                               conf: SketchConf): Option[Sketch[A]] = (sketch, conf) match {
    case (sketch: AdaptiveSketch[A], conf: AdaptiveSketchConf) => AdaptiveSketch.narrowUpdate(sketch, as, conf)
    case _ => super.narrowUpdate(sketch, as, conf)
  }

  override def rearrange[A](sketch: Sketch[A], conf: SketchConf): Option[Sketch[A]] = (sketch, conf) match {
    case (sketch: AdaptiveSketch[A], conf: AdaptiveSketchConf) => AdaptiveSketch.rearrange(sketch, conf)
    case _ => super.rearrange(sketch, conf)
  }

  override def pdf[A](dist: Sketch[A], a: A, conf: SketchConf): Option[Count] = fastPdf(dist, a, conf)

}