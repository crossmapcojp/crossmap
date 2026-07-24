IMPORTANT: When applicable, prefer using intellij-index MCP tools for code navigation and refactoring.

Modules:
* core, app, cli modules are written in Kotlin/Common, so use kotlin standard libraries and KMP libraries only, do not write JDK specific Kotlin/JVM which will not work in other platforms. use KotlinLogging for log.
* crawl, server modules are written in Kotlin/JVM so you can use any JDK features with Kotlin/JVM and any KMP libraries. use logback for log.

Denomination crawler conventions:
* Treat every denomination crawler as a first-class crawler. Do not introduce `Additional*` crawler base types, files, collections, report fields, or other categories for newly implemented denominations.
* Put each concrete denomination crawler in its own file named after the crawler class, for example `FGJADenominationChurchListCrawler.kt`. Do not group multiple denomination crawler implementations into one source file.
* Each concrete crawler must directly implement `SinglePageDenominationChurchListCrawler` or `MultiPageDenominationChurchListCrawler`.
* Keep denomination-specific church-list selectors, row/card interpretation, exclusions, pagination/page URLs, and church-detail-page parsing in that concrete crawler's file. Do not hide these rules in a shared generic parser or generic `enrich` implementation.
* A concrete denomination crawler must construct and enrich `OfficialDenominationChurch` itself. Do not delegate that work to a generic block-to-church function such as `DirectoryCrawlerSupport.churchFromBlock`; the crawler file must visibly map that denomination page's elements/columns to name, address, phone, fax, website, email, social profiles, ministers, jurisdiction, and detail-page URL.
* Shared crawler support may contain only genuinely denomination-independent primitives such as address normalization, phone/email extraction, minister-role parsing, URL classification, HTTP loading, and cache handling. The concrete crawler remains responsible for deciding which elements and fields those primitives apply to.
* Register and report all dedicated denomination crawlers uniformly. Prefer a denomination-id-to-count map over adding one property per crawler or maintaining a separate new-crawler bucket.
* Mark a crawler checkbox complete in `crawl/src/main/kotlin/jp/co/crossmap/crawl/denomination/README.md` only after its parser tests and a live crawl validate the generated data.
