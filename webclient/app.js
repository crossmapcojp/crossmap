const $ = selector => document.querySelector(selector);
const escapeHtml = (value = "") => String(value).replace(/[&<>'"]/g, character => ({
  "&": "&amp;", "<": "&lt;", ">": "&gt;", "'": "&#39;", '"': "&quot;",
})[character]);

const uiLanguage = document.documentElement.lang.toLowerCase().split("-")[0] || "en";
const languageLinks = [...document.querySelectorAll("a[hreflang]:not([hreflang='x-default'])")];
const linkLanguage = link => (link.getAttribute("hreflang") || "").toLowerCase().split("-")[0];
const supportedLanguages = languageLinks.map(linkLanguage).filter(Boolean);

languageLinks.forEach(link => link.addEventListener("click", () => {
  try {
    localStorage.setItem("crossmap.language", linkLanguage(link));
  } catch (_) {
    // Language navigation still works when browser storage is unavailable.
  }
}));

const isInside = (latitude, longitude, south, west, north, east) =>
  latitude >= south && latitude <= north && longitude >= west && longitude <= east;

function languageFromCoordinates(latitude, longitude) {
  if (isInside(latitude, longitude, 33.0, 124.5, 39.5, 130.0)) return "ko";
  if (isInside(latitude, longitude, 30.0, 129.5, 46.5, 146.5)
    || isInside(latitude, longitude, 24.0, 122.0, 30.0, 132.0)) return "ja";
  if (isInside(latitude, longitude, -34.5, -74.0, 5.5, -34.0)) return "pt";
  if (isInside(latitude, longitude, 36.5, -10.0, 42.5, -6.0)
    || isInside(latitude, longitude, 32.0, -17.5, 33.5, -16.0)
    || isInside(latitude, longitude, 36.5, -31.5, 40.5, -24.5)) return "pt";
  const indonesiaRegions = [
    [-6.5, 95.0, 6.5, 106.5],
    [-9.5, 105.0, -5.0, 115.0],
    [-4.5, 108.0, 4.5, 119.0],
    [-6.5, 118.0, 2.5, 125.0],
    [-11.5, 114.0, -7.0, 125.5],
    [-10.8, 124.0, 2.5, 141.5],
  ];
  if (indonesiaRegions.some(bounds => isInside(latitude, longitude, ...bounds))) return "id";
  return "en";
}

if (location.pathname === "/" || location.pathname === "/index.html") {
  const browserLanguages = Array.isArray(navigator.languages) && navigator.languages.length
    ? navigator.languages
    : navigator.language ? [navigator.language] : [];
  const detectedLanguage = browserLanguages
    .map(language => String(language).toLowerCase().split("-")[0])
    .find(language => supportedLanguages.includes(language));
  const redirectToLanguage = language => {
    const destination = languageLinks.find(link => linkLanguage(link) === language)
      || languageLinks.find(link => linkLanguage(link) === "en");
    if (destination) location.replace(destination.href);
  };
  if (detectedLanguage) {
    redirectToLanguage(detectedLanguage);
  } else if (navigator.geolocation) {
    navigator.geolocation.getCurrentPosition(
      position => redirectToLanguage(languageFromCoordinates(position.coords.latitude, position.coords.longitude)),
      () => redirectToLanguage("en"),
      {enableHighAccuracy: false, timeout: 5000, maximumAge: 300000},
    );
  } else {
    redirectToLanguage("en");
  }
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
