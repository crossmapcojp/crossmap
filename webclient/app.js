const $ = (selector) => document.querySelector(selector);
const escapeHtml = (value = "") => value.replace(/[&<>'"]/g, c => ({"&":"&amp;","<":"&lt;",">":"&gt;","'":"&#39;",'"':"&quot;"})[c]);
const supportedLanguages = ["ja", "en", "ko", "pt", "id"];
const browserLanguage = (navigator.language || "en").toLowerCase().split("-")[0];
let selectedLanguage = localStorage.getItem("crossmap.language");
if (!supportedLanguages.includes(selectedLanguage)) selectedLanguage = supportedLanguages.includes(browserLanguage) ? browserLanguage : "en";
const languagePicker = $("#language");
if (languagePicker) {
  languagePicker.value = selectedLanguage;
  languagePicker.addEventListener("change", () => {
    selectedLanguage = languagePicker.value;
    localStorage.setItem("crossmap.language", selectedLanguage);
    location.reload();
  });
}

function displayName(value) {
  const localized = (value.localizedNames || []).find(name => name.languageCode.toLowerCase().split("-")[0] === selectedLanguage);
  if (localized?.name) return localized.name;
  if (selectedLanguage === "en" && value.englishName) return value.englishName;
  return value.name || value.japaneseName || value.englishName || "";
}

function displayDenomination(value) {
  const localized = (value.localizedDenominationNames || []).find(name => name.languageCode.toLowerCase().split("-")[0] === selectedLanguage);
  return localized?.name || value.denominationId || "";
}

async function fetchJson(url) {
  const response = await fetch(url, {headers: {Accept: "application/json"}});
  const body = await response.json();
  if (!response.ok) throw new Error(body.message || `HTTP ${response.status}`);
  return body;
}

const form = $("#search-form");
if (form) {
  form.addEventListener("submit", event => {
    event.preventDefault();
    const query = $("#query").value.trim();
    if (query) location.href = `/result.html?q=${encodeURIComponent(query)}`;
  });
}

const results = $("#results");
if (results) {
  const parameters = new URLSearchParams(location.search);
  const query = parameters.get("q") || "";
  let offset = Math.max(0, Number(parameters.get("offset")) || 0);
  const limit = 20;
  let deviceLocation = null;
  $("#result-title").textContent = query ? `「${query}」の検索結果` : "教会検索";
  const run = async () => {
    if (!query) { $("#status").textContent = "検索語がありません。"; return; }
    $("#status").textContent = "検索中…";
    results.replaceChildren();
    try {
      const locationParameters = deviceLocation ? `&lat=${deviceLocation.latitude}&lon=${deviceLocation.longitude}` : "";
      let data = await fetchJson(`/api/v1/churches/search?q=${encodeURIComponent(query)}&lang=${encodeURIComponent(selectedLanguage)}&offset=${offset}&limit=${limit}${locationParameters}`);
      if (!deviceLocation && data.resolvedLocations.length === 0 && navigator.geolocation) {
        deviceLocation = await new Promise(resolve => navigator.geolocation.getCurrentPosition(
          position => resolve({latitude: position.coords.latitude, longitude: position.coords.longitude}),
          () => resolve(null),
          {enableHighAccuracy: false, timeout: 5000, maximumAge: 300000},
        ));
        if (deviceLocation) {
          const locationQuery = `&lat=${deviceLocation.latitude}&lon=${deviceLocation.longitude}`;
          data = await fetchJson(`/api/v1/churches/search?q=${encodeURIComponent(query)}&lang=${encodeURIComponent(selectedLanguage)}&offset=${offset}&limit=${limit}${locationQuery}`);
        }
      }
      $("#status").textContent = data.total ? `${data.total}件の教会` : "該当する教会はありません。";
      results.innerHTML = data.hits.map(hit => `<article class="result">
        <h2><a href="${escapeHtml(hit.detailUrl || `/church.html?id=${encodeURIComponent(hit.churchId)}`)}" lang="${escapeHtml(selectedLanguage)}">${escapeHtml(displayName(hit))}</a></h2>
        ${selectedLanguage === "en" ? "" : `<p lang="en">${escapeHtml(hit.englishName)}</p>`}
        <p>${escapeHtml(hit.address)}${hit.distanceKm == null ? "" : ` · ${hit.distanceKm.toFixed(1)} km`}</p>
        ${hit.matchedPages?.[0]?.snippet ? `<p class="snippet">${escapeHtml(hit.matchedPages[0].snippet)}</p>` : ""}
      </article>`).join("");
      $("#pagination").innerHTML = `${offset > 0 ? '<button id="previous">前へ</button>' : ''}${offset + data.hits.length < data.total ? '<button id="next">次へ</button>' : ''}`;
      $("#previous")?.addEventListener("click", () => { offset = Math.max(0, offset - limit); history.replaceState(null, "", `?q=${encodeURIComponent(query)}&offset=${offset}`); run(); });
      $("#next")?.addEventListener("click", () => { offset += limit; history.replaceState(null, "", `?q=${encodeURIComponent(query)}&offset=${offset}`); run(); });
    } catch (error) { $("#status").textContent = error.message; }
  };
  run();
}

const detail = $("#church");
if (detail) {
  const id = new URLSearchParams(location.search).get("id");
  if (!id) $("#status").textContent = "教会IDがありません。";
  else fetchJson(`/api/v1/churches/${encodeURIComponent(id)}?lang=${encodeURIComponent(selectedLanguage)}`).then(church => {
    const churchName = displayName(church);
    const denominationName = displayDenomination(church);
    document.title = `${churchName} · Crossmap`;
    $("#status").textContent = "";
    detail.innerHTML = `<p class="eyebrow">CHURCH</p><h1 lang="${escapeHtml(selectedLanguage)}">${escapeHtml(churchName)}</h1>
      ${selectedLanguage === "en" ? "" : `<p lang="en">${escapeHtml(church.englishName)}</p>`}
      ${denominationName ? `<p><strong>教派</strong><br><span lang="${escapeHtml(selectedLanguage)}">${escapeHtml(denominationName)}</span></p>` : ""}
      <p><strong>住所</strong><br>${escapeHtml(church.address)}</p>
      <p><strong>ウェブサイト</strong><br><a href="${escapeHtml(church.websiteUrl)}" rel="noopener">${escapeHtml(church.websiteUrl)}</a></p>
      ${church.socialProfiles?.length ? `<h2>ソーシャル</h2><ul>${church.socialProfiles.map(p => `<li><a href="${escapeHtml(p.url)}" rel="noopener">${escapeHtml(p.platform)}${p.handle ? ` · ${escapeHtml(p.handle)}` : ""}</a></li>`).join("")}</ul>` : ""}`;
  }).catch(error => { $("#status").textContent = error.message; });
}
