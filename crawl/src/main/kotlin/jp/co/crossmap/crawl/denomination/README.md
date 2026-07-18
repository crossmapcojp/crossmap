# Official denomination church-list crawlers

This package contains explicit parsers for authoritative church directories whose HTML structure needs stronger
validation than the generic CSS-selector crawler. The generated lists serve two purposes:

1. add official denomination evidence to a Google Saved Places church when the official row can be matched by
   normalized name plus postal/address evidence;
2. remove a programmatic denomination label when a fresh, complete official directory does not support it.

Human-reviewed denomination determinations always win. Official rows that cannot yet be joined to a complete
`ChurchRecord` remain in the generated list and `cache/cleanup/denomination-candidates.json`; they are not published
with invented coordinates or English names.

## Classes and data flow

- `DenominationChurchListCrawler` is the shared parser contract.
- `OfficialDenominationChurch`, `OfficialChurchMembershipStatus`, and `OfficialDenominationChurchList` are the
  serializable JSON model. A row marked as a pending applicant is retained for review but cannot establish membership.
- `UCCJDenominationChurchListCrawler` parses the `table.kyokai` rows from `https://uccj.org/diocese`.
- `JBCDenominationChurchListCrawler` parses the `table.church-table` rows from `https://bapren.jp/church/`.
- `JACCDenominationChurchListCrawler` parses the multi-row table from `https://db.jacc.info/database/db_list.php`.
- `CachedHttpDenominationChurchPageLoader` stores current HTML and fetch metadata under
  `cache/denomination-church-lists/<denomination>/`.
- `DenominationChurchListCrawlerRunner` invalidates requested caches, loads a page, parses and validates rows, and
  atomically writes `resources/crawl/uccj-churches.json`, `resources/crawl/jbc-churches.json`, or `resources/crawl/jacc-churches.json`.
- `OfficialDenominationChurchListReconciler` performs address-aware, one-official-row-to-one-catalog-record matching.
  It assigns supported memberships, clears unsupported programmatic labels, and preserves human overrides.
- `OfficialDenominationChurchListPipeline` runs both explicit crawlers, replaces stale UCCJ/JBC candidate evidence,
  optionally runs the generic crawlers for other denominations, and reconciles the pending or canonical catalog.

```mermaid
flowchart LR
    U[UCCJ /diocese] --> UL[Fresh HTML cache]
    B[JBC /church/] --> BL[Fresh HTML cache]
    A[JACC /db_list.php] --> AL[Fresh HTML cache]
    UL --> UP[UCCJ parser]
    BL --> BP[JBC parser]
    AL --> AP[JACC parser]
    UP --> UJ[resources/crawl/uccj-churches.json]
    BP --> BJ[resources/crawl/jbc-churches.json]
    AP --> AJ[resources/crawl/jacc-churches.json]
    UJ --> C[Official candidates]
    BJ --> C
    AJ --> C
    G[Google Saved Places pending catalog] --> R[One-to-one official-list reconciler]
    C --> R
    R --> O[Correct denomination fields in churches.json]
    C --> M[Unmatched official rows retained for later enrichment]
```

Run both pages fresh and reconcile the catalog with:

```shell
./gradlew :crawl:run --args='crawl-denomination-directories --force-refresh --dedicated-only'
```

Denomination coverage progress:

- [x] UCCJ 日本基督教団
- [ ] カトリック中央協議会
- [ ] 日本聖公会
- [x] JBC 日本バプテスト連盟
- [~] JACC 日本同盟基督教団
- [ ] 日本アッセンブリーズ・オブ・ゴッド教団
- [ ] イエス之御霊教会教団
- [ ] 日本福音キリスト教会連合
- [ ] セブンスデー・アドベンチスト教団
- [ ] 日本ホーリネス教団
- [ ] 日本キリスト改革派教会
- [ ] 日本キリスト教会
- [ ] 日本イエス・キリスト教団
- [ ] 日本福音ルーテル教会
- [ ] イムマヌエル綜合伝道団
- [ ] The Light of Eternal Agape
- [ ] 聖イエス会
- [ ] 在日大韓基督教会
- [x] JBBC 日本バプテスト・バイブル・フェローシップ
- [ ] 日本フルゴスペル教団
- [ ] 保守バプテスト同盟
- [ ] 日本ナザレン教団
- [ ] 日本バプテスト同盟
- [ ] 日本長老教会
- [ ] 単立ペンテコステ教会フェローシップ
- [ ] 日本福音自由教会協議会
- [ ] 基督兄弟団
- [ ] 日本バプテスト教会連合
- [ ] 日本ハリストス正教会教団
- [ ] 福音伝道教団
- [ ] 救世軍
