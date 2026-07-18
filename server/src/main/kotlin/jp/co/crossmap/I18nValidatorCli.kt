package jp.co.crossmap

import java.nio.file.Path

object I18nValidatorCli {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 1) { "Usage: <i18n-directory>" }
        XmlMessageCatalog.load(Path.of(args.single()))
        println("Validated ${Language.entries.size} UI message catalogs and ${MessageKey.entries.size} keys")
    }
}
