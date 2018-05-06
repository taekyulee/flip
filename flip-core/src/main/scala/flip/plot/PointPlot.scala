package flip.plot

import flip._
import flip.pdf.{Count, Prim}
import flip.range.RangeP
import cats.data.NonEmptyList

import scala.collection.immutable.{TreeMap, TreeSet}
import scala.collection.mutable.ArrayBuffer
import scala.language.postfixOps

trait PointPlot extends Plot {

  def records: Array[(Double, Double)]

  lazy val index: TreeMap[Double, Int] = {
    var i = 0
    val arr = Array.ofDim[(Double, Int)](records.length)
    while (i < records.length) {
      val (x, _) = records.apply(i)
      arr.update(i, (x, i))
      i += 1
    }
    TreeMap(arr: _*)
  }

  override def toString: String = "PointPlot(" + records.map { case (x, y) => s"$x -> $y" }.mkString(", ") + ")"

}

trait PointPlotOps[P <: PointPlot] extends PlotOps[P] with PointPlotLaws[P] {

  def modifyRecords(plot: P, f: Array[(Double, Double)] => Array[(Double, Double)]): P

}

trait PointPlotLaws[P <: PointPlot] { self: PointPlotOps[P] =>

  def map(plot: P, f: (Double, Double) => (Double, Double)): P =
    modifyRecords(
      plot,
      records0 => {
        var i = 0
        val records1 = Array.ofDim[(Double, Double)](records0.length)
        while (i < records0.length) {
          val (x, y) = records0.apply(i)
          records1.update(i, f(x, y))
          i += 1
        }
        records1
      }
    )

  def interpolation(plot: P, x: Double): Double = {
    val records = plot.records

    lazy val intp1 = plot.index.from(x).headOption.flatMap {
      case (_, i2) =>
        if (i2 > 0) {
          val (x1, y1) = records(i2 - 1)
          val (x2, y2) = records(i2)
          Fitting((x1, y1) :: (x2, y2) :: Nil, x)
        } else None
    }
    lazy val intp2 = plot.index.to(x).lastOption.flatMap {
      case (_, i2) =>
        if (i2 + 1 < records.length) {
          val (x1, y1) = records(i2)
          val (x2, y2) = records(i2 + 1)
          Fitting((x1, y1) :: (x2, y2) :: Nil, x)
        } else None
    }

    (intp1 orElse intp2).getOrElse(0.0)
  }

  /**
    * @param i Referencial index for the records of the plot.
    * */
  def referencialInterpolation(plot: P, x: Double, i: Int): Double = {
    val records = plot.records
    def refs(j: Int): Option[List[(Double, Double)]] =
      if (i + j < records.length && i + j - 1 >= 0) {
        Some(records.apply(i + j - 1) :: records.apply(i + j) :: Nil)
      } else if (i + j - 1 < records.length && i + j - 2 >= 0) {
        Some(records.apply(i + j - 2) :: records.apply(i + j - 1) :: Nil)
      } else None

    (refs(1).flatMap(refs1 => Fitting(refs1, x)) orElse
      refs(0).flatMap(refs0 => Fitting(refs0, x)))
      .getOrElse(interpolation(plot, x))
  }

  def add(plots: NonEmptyList[(Double, P)]): P =
    modifyRecords(
      plots.head._2,
      _ => {
        val _plots: Array[(Double, P)] = plots.toList.toArray
        val size = plots.toList.map { case (_, plot) => plot.records.length }.sum

        val records1 = Array.ofDim[(Double, Double)](size)
        val idxs = Array.fill(_plots.length)(0)
        var i = 0

        while (i < size) {
          var j = 0
          var xMin = Double.MaxValue
          var minIdx = 0
          while (j < _plots.length) {
            val (_, plot) = _plots.apply(j)
            val x = if (idxs(j) < plot.records.length) plot.records.apply(idxs(j))._1 else Double.MaxValue
            if (x < xMin) {
              xMin = x
              minIdx = j
            }
            j += 1
          }

          var k = 0
          var y2 = 0.0
          while (k < _plots.length) {
            val (w, _plot) = _plots.apply(k)
            val idx = idxs(k)
            val ref = if (idx < _plot.records.length) idx else _plot.records.length - 1
            y2 += w * referencialInterpolation(_plot, xMin, ref)
            k += 1
          }

          records1.update(i, (xMin, y2))
          idxs.update(minIdx, idxs(minIdx) + 1)
          i += 1
        }

        records1
      }
    )

  def inverse(plot: P): P =
    modifyRecords(
      plot,
      (records0: Array[(Double, Double)]) => {
        var i = 0
        val records1 = Array.ofDim[(Double, Double)](records0.length)
        while (i < records0.length) {
          val (x, y) = records0(i)
          records1.update(i, (y, x))
          i += 1
        }
        records1
      }
    )

  def normalizedCumulative(plot: P): P =
    modifyRecords(
      plot,
      (records0: Array[(Double, Double)]) => {
        var (i, cum) = (0, 0.0)
        var (x1, y1) = (Double.NaN, Double.NaN)
        val sum = integralAll(plot)
        val length = records0.length + 2
        val records1 = Array.ofDim[(Double, Double)](length)
        records1.update(0, (Double.MinValue, 0))
        records1.update(length - 1, (Double.MaxValue, 1))
        while (i < records0.length) {
          val (x2, y2) = records0(i)
          cum += (if (!x1.isNaN && !y1.isNaN) areaPoint(x1, y1, x2, y2) else 0.0) / sum
          records1.update(i + 1, (x2, cum))
          x1 = x2
          y1 = y2
          i += 1
        }
        records1
      }
    )

  def inverseNormalizedCumulative(plot: P): P =
    inverse(normalizedCumulative(plot))

  def integralAll(plot: P): Double = {
    val records = plot.records
    var acc = 0.0
    var (x1, y1) = (Double.NaN, Double.NaN)
    var i = 0
    while (i < records.length) {
      val (x2, y2) = records.apply(i)
      acc += (if (!x1.isNaN && !y1.isNaN) areaPoint(x1, y1, x2, y2) else 0.0)
      x1 = x2
      y1 = y2
      i += 1
    }
    acc
  }

  def areaPoint(x1: Double, y1: Double, x2: Double, y2: Double): Double = {
    if (y1 == 0 && y2 == 0) 0
    else RangeP(x1, x2).roughLength * (y2 / 2 + y1 / 2)
  }

}

object PointPlot extends PointPlotOps[PointPlot] {

  private case class PointPlotImpl(records: Array[(Double, Double)]) extends PointPlot

  def apply(records: Array[(Double, Double)]): PointPlot = safe(records)

  def unsafe(records: Array[(Double, Double)]): PointPlot = PointPlotImpl(records)

  def safe(records: Array[(Double, Double)]): PointPlot = unsafe(records.sortBy(_._1))

  def empty: PointPlot = unsafe(Array.empty[(Double, Double)])

  def deltas(ds: List[(Prim, Count)], window: Double): PointPlot = {
    val sum = ds.map(d => d._2).sum
    val _window = if (window <= 0) 1e-100 else window
    val dsArr1 = ds.sortBy(_._1).toArray

    // merge
    var i = 0
    val diff = if (dsArr1.length > 0) Array.ofDim[Double](dsArr1.length - 1) else Array.empty[Double]
    while (i < dsArr1.length - 1) {
      diff.update(i, dsArr1.apply(i + 1)._1 - dsArr1.apply(i)._1)
      i += 1
    }
    var j = 0
    val dsArr2 = new ArrayBuffer[(Double, Double)]
    while (j < dsArr1.length) {
      val _diff = if (j > 0) diff.apply(j - 1) else Double.MaxValue
      val (x1, count1) = dsArr1.apply(j)
      lazy val (x0, count0) = dsArr2.apply(dsArr2.length - 1)
      if (_diff > window * 2) dsArr2.append((x1, count1))
      else dsArr2.update(dsArr2.length - 1, (x0, count0 + count1))
      j += 1
    }

    // deltas
    var k = 0
    val records = Array.ofDim[(Double, Double)](dsArr2.length * 3)
    while (k < dsArr2.length) {
      val (value, count) = dsArr2.apply(k)
      val x1 = value - (_window / 2)
      val x2 = value
      val x3 = value + (_window / 2)
      val y = if (sum * _window > 0) (count * 2) / _window else 0
      records.update(k * 3, (x1, 0))
      records.update(k * 3 + 1, (x2, y))
      records.update(k * 3 + 2, (x3, 0))
      k += 1
    }

    unsafe(records)
  }

  def modifyRecords(plot: PointPlot, f: Array[(Double, Double)] => Array[(Double, Double)]): PointPlot =
    unsafe(f(plot.records))

}
