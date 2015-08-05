package com.tjd.video
import scala.sys.process._

/**
 * @author jmeritt
 */
class Transcoder(dirName: String, input: java.io.InputStream) {

  val proc = Process(s"/usr/local/bin/ffmpeg -i - -codec:v libx264 -codec:a libfaac -f ssegment -segment_list $dirName/cam.m3u8 -segment_list_flags +live -segment_time 10 $dirName/clip%03d.ts")
  
  def run = {
    proc.run(BasicIO.standard{
        os =>
        BasicIO.transferFully(input, os)
        os.close()
      }).exitValue()
  }
  

}

object Transcoder {
  def apply(dirName: String, input: java.io.InputStream) = {
    Process(s"mkdir $dirName").!
    new Transcoder(dirName, input)
  }
}