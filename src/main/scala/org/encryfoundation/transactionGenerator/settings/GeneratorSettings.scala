package org.encryfoundation.transactionGenerator.settings

import com.comcast.ip4s.{Ipv4Address, Port, SocketAddress}
import com.typesafe.config.{Config, ConfigFactory}
import net.ceedubs.ficus.Ficus._
import net.ceedubs.ficus.readers.ArbitraryTypeReader._
import net.ceedubs.ficus.readers.ValueReader
import org.encryfoundation.transactionGenerator.settings.GeneratorSettings.{ExplorerSettings, LoadSettings, Network, WalletSettings}
import supertagged.TaggedType

//todo: Add validation of mnemonic key
case class GeneratorSettings(networkSettings: Network,
                             loadSettings: LoadSettings,
                             explorerSettings: ExplorerSettings,
                             walletSettings: WalletSettings)

object GeneratorSettings {

  object MnemonicKey extends TaggedType[String]
  object ContractHash extends TaggedType[String]
  type MnemonicKey = MnemonicKey.Type
  type ContractHash = ContractHash.Type

  val configPath: String = "encry"

  implicit val inetSocketAddressReader: ValueReader[SocketAddress[Ipv4Address]] = { (config: Config, path: String) =>
    val split = config.getString(path).split(":")
    //todo: remove get
    SocketAddress(Ipv4Address(split(0)).get, Port(split(1).toInt).get)
  }

  implicit val mnemonicKeyReader: ValueReader[MnemonicKey] = {(config: Config, path: String) =>
    MnemonicKey @@ config.getString(path)
  }

  case class Network(peers: List[SocketAddress[Ipv4Address]], bindPort: Int)
  case class LoadSettings(tps: Double)
  case class ExplorerSettings(address: SocketAddress[Ipv4Address])
  case class WalletSettings(mnemonicKeys: List[MnemonicKey])

  def loadConfig(configName: String): GeneratorSettings =
    ConfigFactory
      .load(configName)
      .withFallback(ConfigFactory.load())
      .as[GeneratorSettings](configPath)

}
