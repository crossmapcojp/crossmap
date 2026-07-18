const $ = selector => document.querySelector(selector);
const escapeHtml = (value = "") => String(value).replace(/[&<>'"]/g, character => ({
  "&": "&amp;", "<": "&lt;", ">": "&gt;", "'": "&#39;", '"': "&quot;",
})[character]);

const uiLanguage = document.documentElement.lang.toLowerCase().split("-")[0] || "en";
const languageLinks = [...document.querySelectorAll("a[hreflang]:not([hreflang='x-default'])")];
const linkLanguage = link => (link.getAttribute("hreflang") || "").toLowerCase().split("-")[0];
const supportedLanguages = languageLinks.map(linkLanguage).filter(Boolean);

languageLinks.forEach(link => link.addEventListener("click", () => {
  localStorage.setItem("crossmap.language", linkLanguage(link));
}));

if (location.pathname === "/" || location.pathname.endsWith("/index.html") && !supportedLanguages.includes(uiLanguage)) {
  const explicitLanguage = localStorage.getItem("crossmap.language");
  const browserLanguage = (navigator.language || "en").toLowerCase().split("-")[0];
  const suggested = supportedLanguages.includes(explicitLanguage)
    ? explicitLanguage
    : supportedLanguages.includes(browserLanguage) ? browserLanguage : "en";
  document.querySelector(`a[hreflang="${CSS.escape(suggested)}"]`)?.setAttribute("aria-current", "true");
}

const messagesElement = $("#page-messages");
const messages = messagesElement ? JSON.parse(messagesElement.textContent) : {};
const format = (template, values) => Object.entries(values).reduce(
  (result, [key, value]) => result.split(`{${key}}`).join(value),
  template || "",
);

function localizedValue(values, fallbackEnglish, fallbackJapanese) {
  const normalized = values || [];
  const find = language => normalized.find(value => value.languageCode.toLowerCase().split("-")[0] === language)?.name?.trim();
  return find(uiLanguage) || fallbackEnglish?.trim() || find("en") || fallbackJapanese?.trim() || find("ja")
    || normalized.find(value => value.name?.trim())?.name.trim() || "";
}

async function fetchJson(url) {
  const response = await fetch(url, {headers: {Accept: "application/json"}});
  const body = await response.json();
  if (!response.ok) {
    const error = new Error("request_failed");
    error.code = body.code;
    throw error;
  }
  return body;
}

const results = $("#results");
if (results) {
  const parameters = new URLSearchParams(location.search);
  const query = parameters.get("q") || "";
  let offset = Math.max(0, Number(parameters.get("offset")) || 0);
  const limit = 20;
  let deviceLocation = null;
  const heading = $("#result-heading");
  heading.textContent = format(messages.searchResultsTitle, {query});
  languageLinks.forEach(link => {
    const target = new URL(link.href);
    target.search = location.search;
    link.href = target.pathname + target.search;
  });

  const run = async () => {
    if (!query) {
      $("#status").textContent = messages.noResults || "";
      return;
    }
    $("#status").textContent = messages.loading || "";
    results.replaceChildren();
    try {
      const locationParameters = deviceLocation ? `&lat=${deviceLocation.latitude}&lon=${deviceLocation.longitude}` : "";
      let data = await fetchJson(`/api/v1/churches/search?q=${encodeURIComponent(query)}&lang=${encodeURIComponent(uiLanguage)}&offset=${offset}&limit=${limit}${locationParameters}`);
      if (!deviceLocation && data.resolvedLocations.length === 0 && navigator.geolocation) {
        deviceLocation = await new Promise(resolve => navigator.geolocation.getCurrentPosition(
          position => resolve({latitude: position.coords.latitude, longitude: position.coords.longitude}),
          () => resolve(null),
          {enableHighAccuracy: false, timeout: 5000, maximumAge: 300000},
        ));
        if (deviceLocation) {
          data = await fetchJson(`/api/v1/churches/search?q=${encodeURIComponent(query)}&lang=${encodeURIComponent(uiLanguage)}&offset=${offset}&limit=${limit}&lat=${deviceLocation.latitude}&lon=${deviceLocation.longitude}`);
        }
      }
      $("#status").textContent = data.total
        ? format(messages.searchResultsCount, {count: data.total})
        : messages.noResults;
      results.innerHTML = data.hits.map(hit => {
        const name = localizedValue(hit.localizedNames, hit.englishName, hit.name);
        const detailUrl = hit.detailUrl || "#";
        return `<article class="result"><h2><a href="${escapeHtml(detailUrl)}" lang="${escapeHtml(uiLanguage)}">${escapeHtml(name)}</a></h2>
          <p>${escapeHtml(hit.address)}${hit.distanceKm == null ? "" : ` · ${escapeHtml(format(messages.distanceKm, {distance: hit.distanceKm.toFixed(1)}))}`}</p>
          ${hit.matchedPages?.[0]?.snippet ? `<p class="snippet">${escapeHtml(hit.matchedPages[0].snippet)}</p>` : ""}</article>`;
      }).join("");
      $("#pagination").innerHTML = `${offset > 0 ? `<button id="previous">${escapeHtml(messages.previousPage)}</button>` : ""}${offset + data.hits.length < data.total ? `<button id="next">${escapeHtml(messages.nextPage)}</button>` : ""}`;
      $("#previous")?.addEventListener("click", () => changePage(Math.max(0, offset - limit)));
      $("#next")?.addEventListener("click", () => changePage(offset + limit));
    } catch (error) {
      $("#status").textContent = error.code === "index_unavailable" ? messages.indexUnavailable : messages.serverError;
    }
  };
  const changePage = nextOffset => {
    offset = nextOffset;
    history.replaceState(null, "", `?q=${encodeURIComponent(query)}&offset=${offset}`);
    run();
  };
  run();
}
