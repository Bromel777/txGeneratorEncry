package org.encryfoundation.transactionGenerator.settings

import com.comcast.ip4s.{Ipv4Address, Port, SocketAddress}
import com.typesafe.config.{Config, ConfigFactory}
import net.ceedubs.ficus.Ficus._
import net.ceedubs.ficus.readers.ArbitraryTypeReader._
import net.ceedubs.ficus.readers.ValueReader


object GeneratorSettings {

  val configPath: String = "encry"

  implicit val inetSocketAddressReader: ValueReader[SocketAddress[Ipv4Address]] = { (config: Config, path: String) =>
    val split = config.getString(path).split(":")
    //todo: remove get
    SocketAddress(Ipv4Address(split(0)).get, Port(split(1).toInt).get)
  }

  case class Network(peers: List[SocketAddress[Ipv4Address]])

  def loadConfig(configName: String): Network =
    ConfigFactory
      .load(configName)
      .withFallback(ConfigFactory.load())
      .as[Network](configPath)

}
