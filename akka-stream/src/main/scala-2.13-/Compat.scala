package reactivemongo.akkastream

private[akkastream] object Compat {
  type SerPack = reactivemongo.api.SerializationPack with Singleton
}
