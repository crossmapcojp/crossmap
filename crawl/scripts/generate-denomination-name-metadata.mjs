import fs from "node:fs";
import path from "node:path";

const root = path.resolve(import.meta.dirname, "../..");
const catalogDir = path.join(root, "resources/catalog");
const denominations = JSON.parse(fs.readFileSync(path.join(catalogDir, "denominations.json"), "utf8"))
  .filter(({ id }) => id !== "NOT_DETERMINED" && id !== "INDEPENDENT_CHURCH");
const names = Object.fromEntries(
  ["ja", "en", "ko", "pt", "id"].map(language => [
    language,
    JSON.parse(fs.readFileSync(path.join(catalogDir, `denomination-${language}-names.json`), "utf8")),
  ]),
);

const officialEnglishSources = new Map([
  ["UCCJ", "https://uccj.org/about_uccj"],
  ["CATHOLIC_JP", "https://www.cbcj.catholic.jp/english/japan/"],
  ["ANGLICAN_JP", "https://www.nskk.org/province/en_index.html"],
  ["JAG", "https://j-ag.org/home/"],
  ["SDA_JP", "https://adventist.jp/english-2/"],
  ["COTN_JP", "https://www.nazarene.or.jp/ns1/index.html"],
  ["ORTHODOX_JP", "https://www.orthodoxjapan.jp/"],
  ["SA_JP", "https://www.salvationarmy.or.jp/english-home/aboutus/"],
  ["JBC", "https://bapren.jp/about/joumu/%E5%B8%B8%E5%8B%99%E7%90%86%E4%BA%8B%E5%AE%A4%EF%BC%88%E8%8B%B1%E8%AA%9E%EF%BC%89"],
  ["JACC", "https://domei.site/wp/2021/07/14/%E5%AE%A3%E6%95%99%EF%BC%91%EF%BC%93%EF%BC%90%E5%91%A8%E5%B9%B4%E8%A8%98%E5%BF%B5%E5%A4%A7%E4%BC%9A%E3%83%BB%E3%83%9B%E3%83%BC%E3%83%A0%E3%83%9A%E3%83%BC%E3%82%B8%E5%AE%8C%E6%88%90/"],
  ["JHC", "https://jhc.or.jp/_src/6612715/%E6%97%A5%E6%9C%AC%E3%83%9B%E3%83%BC%E3%83%AA%E3%83%8D%E3%82%B9%E6%95%99%E5%9B%A3%E3%81%AE%E6%88%A6%E4%BA%89%E8%B2%AC%E4%BB%BB%E3%81%AB%E9%96%A2%E3%81%99%E3%82%8B%E7%A7%81%E3%81%9F%E3%81%A1%E3%81%AE%E5%91%8A%E7%99%BD.pdf"],
  ["JELC", "https://jelc.or.jp/welcome-to-the-jelc/"],
  ["IGM", "https://www.immanuel.or.jp/"],
  ["KCCJ", "https://www.kccj.jp/upload_files/osirase/%E7%AC%AC51%E5%9B%9E%E5%AE%9A%E6%9C%9F%E7%B7%8F%E4%BC%9A%E7%9B%AE%E6%AC%A1%EF%BD%A5%E5%BC%8F%E9%A0%86201112-26-30.pdf"],
  ["JCBA", "https://doumei.holy.jp/"],
  ["WJELC", "https://www.wjelc.or.jp/arashima/"],
  ["JLC", "https://www.jlc.or.jp/english/"],
  ["KELC", "https://www.kelc.net/"],
  ["FMC_JP", "https://fmcjp.org/"],
  ["NSKK", "https://tsuyama.nskk.gr.jp/"],
  ["XLSX_E6B8AA1C0D52", "https://gmi.or.jp/about/"],
  ["JECU", "https://church.ne.jp/jecu/"],
  ["JBA", "https://www.jbaptist.org/"],
]);

const traditions = [
  ["CATHOLIC", /カトリック/],
  ["ANGLICAN", /聖公会/],
  ["ORTHODOX", /正教/],
  ["LUTHERAN", /ルーテル|ルター/],
  ["BAPTIST", /バプテスト/],
  ["PRESBYTERIAN", /長老/],
  ["REFORMED", /改革/],
  ["METHODIST", /メソジスト|メソヂスト|ウェスレアン/],
  ["MENNONITE", /メノナイト/],
  ["ADVENTIST", /アドベンチスト/],
  ["ASSEMBLIES_OF_GOD", /アッセンブリーズ・オブ・ゴッド|アッセンブリー・オブ・ゴッド/],
  ["NAZARENE", /ナザレン/],
  ["SALVATION_ARMY", /救世軍/],
  ["FULL_GOSPEL", /純福音|フルゴスペル/],
  ["PENTECOSTAL", /ペンテコステ|ペンテコスタル/],
  ["HOLINESS", /ホーリネス|聖潔/],
];

const output = Object.fromEntries(denominations.map(denomination => {
  const id = denomination.id;
  for (const language of Object.keys(names)) {
    if (!names[language][id]?.trim()) throw new Error(`${id} is missing ${language}`);
  }
  const tradition = traditions.find(([, pattern]) => pattern.test(names.ja[id]))?.[0] ?? null;
  const japaneseEvidence = denomination.officialWebsite
    ? { method: "OFFICIAL_WEBSITE", sourceUrl: denomination.officialWebsite }
    : {
        method: "ESTABLISHED_USAGE",
        note: "Human-curated Japanese denomination name retained from the Crossmap source catalog.",
      };
  const officialEnglish = officialEnglishSources.get(id);
  const englishEvidence = officialEnglish
    ? { method: "OFFICIAL_WEBSITE", sourceUrl: officialEnglish }
    : { method: "TRANSLATED", note: "Reviewed English organization-name translation; official English usage not yet found." };
  return [id, {
    tradition,
    evidence: {
      ja: japaneseEvidence,
      en: englishEvidence,
      ko: { method: "TRANSLATED", note: "Reviewed Korean organization-name translation." },
      pt: { method: "TRANSLATED", note: "Reviewed Portuguese organization-name translation." },
      id: { method: "TRANSLATED", note: "Reviewed Indonesian organization-name translation." },
    },
  }];
}));

fs.writeFileSync(
  path.join(catalogDir, "denomination-name-metadata.json"),
  `${JSON.stringify(output, null, 2)}\n`,
);
console.log(`Wrote ${Object.keys(output).length} denomination metadata records.`);
