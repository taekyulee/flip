package flip.conf.pdf

import flip.pdf.AdaptiveSketch

/**
  * A configuration for AdaptiveSketch.
  * */
trait AdaptiveSketchConfB[+D <: AdaptiveSketch[_]] extends SketchConf {

  val bufferSize: Int

}
