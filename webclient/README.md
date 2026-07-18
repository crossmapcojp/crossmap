# Web client

`webclient/` is both the Ktor development web root and the Cloudflare Pages production artifact.

Source templates live in `server/src/main/resources/index.html`, `result.html`, and `church.html`. `:server:generateChurchPages` renders a root page that first matches the browser language, then uses browser coordinates for Japan, Korea, Indonesia, Brazil, or Portugal, and otherwise falls back to English. It retains its chooser for no-JavaScript clients and creates one single-language tree for each `Language` (`ja`, `en`, `ko`, `pt`, and `id`). Generated language directories, `manifest.json`, and `sitemap.xml` are ignored by Git and must be rebuilt for deployment.

`app.js` is language agnostic. Generated pages provide their language and messages; JavaScript handles search state and calls the same-origin `/api/v1/` API. Church details and all SEO-critical content are already present in HTML and do not depend on JavaScript.

Development:

```sh
./gradlew :server:run
```

Cloudflare Pages production build:

```sh
./gradlew :server:generateChurchPages -PcrossmapSiteBaseUrl=https://www.crossmap.co.jp
```

Set the Pages output directory to `webclient`. Route `/api/v1/*` and the snapshot download endpoints to the Ktor service on the same origin.
