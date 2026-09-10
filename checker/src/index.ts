/**
 * KiloProxy exit-IP checker (Cloudflare Worker).
 *
 * The app routes this request THROUGH the SOCKS proxy, so the Worker sees
 * the proxy's exit IP as the client. No lookup needed: Cloudflare hands us
 * the IP plus geo/ASN enrichment on every request (request.cf).
 *
 * Contract: GET -> 200 JSON with exactly these keys (app parses all of
 * them; empty string when Cloudflare has no value):
 *   ip, countryCode, country, regionName, city, isp, org, asName, timezone
 *
 * Freshness: Cache-Control: no-store. The app wants a live exit-IP read;
 * any edge caching would reintroduce stale geo.
 *
 * Zero dependencies on purpose: no router, no validation lib. This is the
 * fastest shape for this workload (~0.1ms CPU, well under the 10ms free
 * quota). Deploy: npx wrangler deploy (from the checker/ folder).
 */

interface Env {
  CHECK_KEY?: string;
}

// request.cf is untyped without @cloudflare/workers-types (kept
// dependency-free on purpose); read it defensively.
function cfStr(cf: unknown, key: string): string {
  if (typeof cf !== "object" || cf === null) return "";
  const v: unknown = (cf as Record<string, unknown>)[key];
  return typeof v === "string" ? v : "";
}

function cfAsn(cf: unknown): string {
  if (typeof cf !== "object" || cf === null) return "";
  const v: unknown = (cf as Record<string, unknown>)["asn"];
  if (typeof v === "number") return "AS" + v;
  if (typeof v === "string" && v.length > 0) return v.startsWith("AS") ? v : "AS" + v;
  return "";
}

// ISO-3166 alpha-2 -> short English name. Cloudflare only sends the code;
// the map keeps the endpoint a complete ip-api replacement. XX/T1 are
// Cloudflare's own "unknown" / "Tor exit" markers.
const COUNTRY_NAMES: Record<string, string> = {
  AD: "Andorra", AE: "United Arab Emirates", AF: "Afghanistan", AG: "Antigua and Barbuda",
  AI: "Anguilla", AL: "Albania", AM: "Armenia", AO: "Angola", AQ: "Antarctica",
  AR: "Argentina", AS: "American Samoa", AT: "Austria", AU: "Australia", AW: "Aruba",
  AX: "Aland Islands", AZ: "Azerbaijan", BA: "Bosnia and Herzegovina", BB: "Barbados",
  BD: "Bangladesh", BE: "Belgium", BF: "Burkina Faso", BG: "Bulgaria", BH: "Bahrain",
  BI: "Burundi", BJ: "Benin", BL: "Saint Barthelemy", BM: "Bermuda", BN: "Brunei",
  BO: "Bolivia", BQ: "Bonaire Sint Eustatius and Saba", BR: "Brazil", BS: "Bahamas",
  BT: "Bhutan", BV: "Bouvet Island", BW: "Botswana", BY: "Belarus", BZ: "Belize",
  CA: "Canada", CC: "Cocos Islands", CD: "DR Congo", CF: "Central African Republic",
  CG: "Republic of the Congo", CH: "Switzerland", CI: "Ivory Coast", CK: "Cook Islands",
  CL: "Chile", CM: "Cameroon", CN: "China", CO: "Colombia", CR: "Costa Rica",
  CU: "Cuba", CV: "Cape Verde", CW: "Curacao", CX: "Christmas Island", CY: "Cyprus",
  CZ: "Czechia", DE: "Germany", DJ: "Djibouti", DK: "Denmark", DM: "Dominica",
  DO: "Dominican Republic", DZ: "Algeria", EC: "Ecuador", EE: "Estonia", EG: "Egypt",
  EH: "Western Sahara", ER: "Eritrea", ES: "Spain", ET: "Ethiopia", FI: "Finland",
  FJ: "Fiji", FK: "Falkland Islands", FM: "Micronesia", FO: "Faroe Islands",
  FR: "France", GA: "Gabon", GB: "United Kingdom", GD: "Grenada", GE: "Georgia",
  GF: "French Guiana", GG: "Guernsey", GH: "Ghana", GI: "Gibraltar", GL: "Greenland",
  GM: "Gambia", GN: "Guinea", GP: "Guadeloupe", GQ: "Equatorial Guinea", GR: "Greece",
  GS: "South Georgia", GT: "Guatemala", GU: "Guam", GW: "Guinea-Bissau", GY: "Guyana",
  HK: "Hong Kong", HM: "Heard Island and McDonald Islands", HN: "Honduras", HR: "Croatia",
  HT: "Haiti", HU: "Hungary", ID: "Indonesia", IE: "Ireland", IL: "Israel",
  IM: "Isle of Man", IN: "India", IO: "British Indian Ocean Territory", IQ: "Iraq",
  IR: "Iran", IS: "Iceland", IT: "Italy", JE: "Jersey", JM: "Jamaica", JO: "Jordan",
  JP: "Japan", KE: "Kenya", KG: "Kyrgyzstan", KH: "Cambodia", KI: "Kiribati",
  KM: "Comoros", KN: "Saint Kitts and Nevis", KP: "North Korea", KR: "South Korea",
  KW: "Kuwait", KY: "Cayman Islands", KZ: "Kazakhstan", LA: "Laos", LB: "Lebanon",
  LC: "Saint Lucia", LI: "Liechtenstein", LK: "Sri Lanka", LR: "Liberia", LS: "Lesotho",
  LT: "Lithuania", LU: "Luxembourg", LV: "Latvia", LY: "Libya", MA: "Morocco",
  MC: "Monaco", MD: "Moldova", ME: "Montenegro", MF: "Saint Martin", MG: "Madagascar",
  MH: "Marshall Islands", MK: "North Macedonia", ML: "Mali", MM: "Myanmar", MN: "Mongolia",
  MO: "Macao", MP: "Northern Mariana Islands", MQ: "Martinique", MR: "Mauritania",
  MS: "Montserrat", MT: "Malta", MU: "Mauritius", MV: "Maldives", MW: "Malawi",
  MX: "Mexico", MY: "Malaysia", MZ: "Mozambique", NA: "Namibia", NC: "New Caledonia",
  NE: "Niger", NF: "Norfolk Island", NG: "Nigeria", NI: "Nicaragua", NL: "Netherlands",
  NO: "Norway", NP: "Nepal", NR: "Nauru", NU: "Niue", NZ: "New Zealand", OM: "Oman",
  PA: "Panama", PE: "Peru", PF: "French Polynesia", PG: "Papua New Guinea",
  PH: "Philippines", PK: "Pakistan", PL: "Poland", PM: "Saint Pierre and Miquelon",
  PN: "Pitcairn Islands", PR: "Puerto Rico", PS: "Palestine", PT: "Portugal",
  PW: "Palau", PY: "Paraguay", QA: "Qatar", RE: "Reunion", RO: "Romania",
  RS: "Serbia", RU: "Russia", RW: "Rwanda", SA: "Saudi Arabia", SB: "Solomon Islands",
  SC: "Seychelles", SD: "Sudan", SE: "Sweden", SG: "Singapore", SH: "Saint Helena",
  SI: "Slovenia", SJ: "Svalbard and Jan Mayen", SK: "Slovakia", SL: "Sierra Leone",
  SM: "San Marino", SN: "Senegal", SO: "Somalia", SR: "Suriname", SS: "South Sudan",
  ST: "Sao Tome and Principe", SV: "El Salvador", SX: "Sint Maarten", SY: "Syria",
  SZ: "Eswatini", TC: "Turks and Caicos Islands", TD: "Chad", TF: "French Southern Territories",
  TG: "Togo", TH: "Thailand", TJ: "Tajikistan", TK: "Tokelau", TL: "Timor-Leste",
  TM: "Turkmenistan", TN: "Tunisia", TO: "Tonga", TR: "Turkey", TT: "Trinidad and Tobago",
  TV: "Tuvalu", TW: "Taiwan", TZ: "Tanzania", UA: "Ukraine", UG: "Uganda",
  UM: "US Minor Outlying Islands", US: "United States", UY: "Uruguay", UZ: "Uzbekistan",
  VA: "Vatican City", VC: "Saint Vincent and the Grenadines", VE: "Venezuela",
  VG: "British Virgin Islands", VI: "US Virgin Islands", VN: "Vietnam", VU: "Vanuatu",
  WF: "Wallis and Futuna", WS: "Samoa", XK: "Kosovo", YE: "Yemen", YT: "Mayotte",
  ZA: "South Africa", ZM: "Zambia", ZW: "Zimbabwe", XX: "Unknown", T1: "Tor Exit",
};

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    if (request.method !== "GET") {
      return new Response("Method not allowed", { status: 405 });
    }
    if (env.CHECK_KEY && request.headers.get("X-Check-Key") !== env.CHECK_KEY) {
      return new Response("Forbidden", { status: 403 });
    }
    const cf: unknown = (request as unknown as Record<string, unknown>)["cf"] || {};
    const code = cfStr(cf, "country").toUpperCase();
    const org = cfStr(cf, "asOrganization");
    const body = {
      ip: request.headers.get("CF-Connecting-IP") || "",
      countryCode: code,
      country: COUNTRY_NAMES[code] || "",
      regionName: cfStr(cf, "region"),
      city: cfStr(cf, "city"),
      isp: org,
      org: org,
      asName: cfAsn(cf),
      timezone: cfStr(cf, "timezone"),
    };
    return new Response(JSON.stringify(body), {
      headers: {
        "Content-Type": "application/json",
        "Cache-Control": "no-store",
      },
    });
  },
};
