This dir contains dictionaries which is helps JP-EN translation of words which is difficult to detect programatically, and also difficult for LLMs.
Following

### ja-en-churchname-dictionary.csv
In some rare case, some church has official English name which is not possible to translate programatically, or llm can not estimate this dictionary has such exceptional case JA-EN name pairs. (e.g. 聖書キリスト教会,Biblical Church of Tokyo)

### ja-en-concept-dictionary.csv
* word is conined word (from the concepts of Bible)
* rarely used word because it is christendom jargon
* words specific to a denomination. e.g. 聖約,Seiyaku is specific to 日本聖約キリスト教団, e.g. 神召,Shinsho is specific to AOG, e.g. オンヌリ,Onnuri is specific for Onnuri Church
* Names of the Saints usually parts of the NSKK church name. e.g. 聖マルコ,St. Mark

### `<source>-<target>-<category>-dictionary.csv`

Reviewed multilingual pairs use ISO language codes and one of `churchname`, `concept`, or `geoname`, for example `ja-en-geoname-dictionary.csv` and `ja-ko-concept-dictionary.csv`. The loader also makes a reverse view when an explicit reverse-direction file does not exist.

The Japanese concept dictionaries for `en`, `ko`, `pt`, `es`, and `id` share the complete Japanese key set from `ja-en-concept-dictionary.csv`. `ChurchNameEnglishDictionaryTest` rejects a missing target-language file, a missing Japanese concept, a blank translation, or a duplicate entry.

Geoname dictionaries contain daily-life regions and historical names not covered reliably by GeoNames (e.g. 湘南/Shonan, 京阪奈/Keihanna, 丹後/Tango).

### congregation-terms.json

Canonical congregation/name-component terminology with aliases by language. JSON is used because one concept has multiple spellings in each language (`教会`/`チャーチ`, `Assembleia`/`Assembléia`) and is shared across ja, en, ko, pt, id, and other languages.

Format:

聖和,Seiwa
まぶね,Mabune

*dictionary.csv entries can be added or edited by human or a AI Agent like Codex. and git commited because the size is small.
