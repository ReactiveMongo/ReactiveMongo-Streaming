package reactivemongo.play.iteratees

import reactivemongo.api.bson.Digest

package object tests {
  @inline def md5Hex(bytes: Array[Byte]): String =
    Digest.hex2Str(Digest.md5(bytes))
}
