TODO_MULTI_PART_NAME_PROCESSING.md

In the Data Cleanup pipeline right We need data cleanup step to decompose multiple part names:

Those are something like PartA1 PartA2 PartA3... (PartB1, PartB2, Part B3 ...) and its variants.
Actually in some case we need to modify the data cleanup process of GoogleSavedPlacesSeedReader.kt
because japanese name field in the seed church data needs to have only Japanese, but current implementation allows
English+Japanese name and other combinations in the japanese name field.
We need to implement 1. determine if a google place name is name following pattern or not and extract Japanese name, then
if there are english name, use it later.
Also, if the name is only latin script name, we need to translate/translitaerate that into Japanese name instead of putting latin script name into japanese name, so that later japanese name will put into japanese name field, latin script name will be in english name field during GoogleSavedPlacesSeedReader.kt processing
Also, I noticed that there are many non-Japanese, non-English language names. Language detection uses the vendored Cybozu/Shuyo detector with its 47 short-text profiles; Japanese and Korean scripts are classified deterministically before statistical detection.
After splitting parts by "(" and ")" or "|" or "huristic white space detection logic", detect language of parts, then proceed to the next steps, also preserve the none-japanese, non-english name as record with 2 letter language code and display that name in the webclient annd app together with Japanese name.


I will describe the pattern, example of the churchname -> expcted English translation below:

- [x] English Name (Japanese Name) and English Name Abbrebeation (Japanese Name)
  English Name could be any Latin script Name
  e.g. Just Church（ジャスト・チャーチ）-> Just Church
  e.g. IEQ Chuo Gospel Church (中央フォースクエア福音教会）-> IEQ Chuo Gospel Church
  e.g. Gospel Life Church (ゴスペルライフチャーチ) -> Gospel Life Church
  e.g. Gereja Interdenominasi Injili Indonesia (GIII) Oarai (大洗インドネシア福音教会 ) -> Gereja Interdenominasi Injili Indonesia

- [x] English Name [just a white space] Japanese Name (this type we need to implement heuristic to see multi words similar length in latin then japanese to determine, write unit test well for the logic)
  e.g. Be One Hokusetsu ビーワン北摂キリスト教会 -> Be One Hokusetsu
  e.g. Sakuragi Christian Center 桜木クリスチャンセンター -> Sakuragi Christian Center
  e.g. Lifehouse International Church Osaka ライフハウス大阪 -> Lifehouse International Church Osaka
  e.g. Calvary Baptist Church カルバリバプテスト教会 -> Calvary Baptist Church

- [x] Latin Abbrebiation (Latin script name)
  e.g. IEN(Igreja Evangélica das Nações) -> Igreja Evangélica das Nações

- [x] Japanese Name | English Name
  e.g. マスタードシードクリスチャン教会 さいたま | MUSTARD SEED Christian Church Saitama -> MUSTARD SEED Christian Church Saitama

- [x] Japanese Name (Korean Name)
  マリ キリスト教会 (마리 그리스도교회) -> Mari Christ Church

- [x] Korean Name (Japanese Name)
  동경지구촌교회 (東京ジグチョン教会) -> Tokyo Jiguchon Church

- [x] Japanese Name (branch name + a word which means building)
  e.g. 東京日暮里国際教会(六本木会堂) -> Tokyo Nippori International Church Roppongi Chapel
  e.g. 改革派国際基督長老教会(西東京礼拝堂) -> Reformed International Christian Presbyterian Church Nishitokyo Chapel


- [x] Japanese Name which is difficult to tell if that is a church or not (Christian Church)
  e.g. ザ・クラウドチャーチ(キリスト教会) -> The Cloud Church
  e.g. ベイサイドチャーチ（キリスト教会）-> Bay Side Church

- [x] Japanese Name with english abbreb (Japanese full name)
  e.g. 別府EMC(別府地の果て宣教教会）-> Beppu End Of The Earth Mission Church

- [x] Japanese Name with english abbreb Japanese full name
  e.g. 東京EMC 東京地の果て宣教教会 -> Tokyo End Of The Earth Mission Church

- [x] Japanese Full Name (Japanese other full name)
  e.g. 寝屋川福音キリスト教会 (ファミリーチャーチねや川) -> Neyagawa Gospel Christ Church

- [x] Latin abbreviation + Japanese church name + trailing geoname branch without a building suffix
  e.g. HCCライブチャーチ寸座 -> HCCライブチャーチ寸座
  e.g. HCCライブチャーチ津山 -> HCCライブチャーチ津山

- [x] Latin-script church name decomposed into reviewed concepts, structural words, geonames, and fallback proper-name parts
- [x] Raw Saved Places titles are decomposed only during Google Maps resolution so every localized name is derived from the same authoritative title and place evidence.
- [x] Each component records denomination, geoname, concept, congregation, church-name, or unresolved role and all deterministic target-language translations.
- [x] Japanese, English, Korean, Portuguese, and Indonesian compositions use generic language-pair dictionaries plus multilingual congregation terms, with detected source-language originals preserved.
- [x] Google Maps resolution logs name-pattern, detected-language, component-role, and unresolved-component coverage statistics.
  e.g. Grace Center Church Sendai -> グレースセンターチャーチ仙台
  e.g. Global Mission Japan -> グローバルミッションジャパン
