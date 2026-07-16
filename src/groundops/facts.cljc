(ns groundops.facts
  "Per-jurisdiction airport/ground-handling authority spec-basis catalog
  -- the G2-style table the Airport Ground Operations Governor checks
  every proposal against ('did the advisor cite an OFFICIAL public
  source for this ground-handling engagement's operating jurisdiction,
  or did it invent one?').

  This is NOT an aerodrome-certification authority and does NOT itself
  verify an airport-facility permit or a ground-handling-operator
  license -- see `groundops.store`'s `:facility-verified?` ground-truth
  fact, which this actor treats as independently registered elsewhere
  (a real airport-operator/civil-aviation-authority record this actor
  consumes, never mints). This catalog only answers 'is there an
  official regulatory basis for coordinating ground-handling operations
  in this jurisdiction at all' -- the honest, non-fabricating
  discipline every sibling actor's `facts` namespace uses.

  Coverage is reported HONESTLY (see `coverage`): a jurisdiction not in
  this table has NO spec-basis, full stop -- the advisor must not
  fabricate one, and the governor holds if it tries."
  (:require [clojure.string :as str]))

(def catalog
  "iso3 -> requirement map. `:required-evidence` mirrors the generic
  airport-facility-permit / ground-handling-operator-license /
  ground-support-equipment-operator-qualification / safety-management-
  system evidence set a real airport-operator or civil aviation
  authority requires before a ground-handling engagement can be
  coordinated in that jurisdiction. `:legal-basis` / `:owner-authority`
  / `:provenance` are the G2 citation the governor requires before any
  proposal can commit."
  {"JPN" {:name "Japan"
          :owner-authority "国土交通省航空局 (Japan Civil Aviation Bureau, JCAB)"
          :legal-basis "空港法 (Airport Act)"
          :national-spec "空港土木施設技術基準・地上取扱業に関する安全基準"
          :provenance "https://www.mlit.go.jp/koku/koku_fr8_000005.html"
          :required-evidence ["空港施設許可記録 (airport-facility-permit record)"
                              "地上取扱業許可記録 (ground-handling-operator license record)"
                              "特殊車両運行資格記録 (ground-support-equipment operator qualification record)"
                              "安全管理システム記録 (safety-management-system record)"]}
   "USA" {:name "United States"
          :owner-authority "Federal Aviation Administration (FAA)"
          :legal-basis "14 C.F.R. Part 139 (Certification of Airports)"
          :national-spec "FAA Advisory Circular 150/5210 series ground-safety standards"
          :provenance "https://www.faa.gov/airports/airport_safety/part139_cert/"
          :required-evidence ["Airport-facility-permit record (Part 139 Airport Operating Certificate)"
                              "Ground-handling-operator authorization record"
                              "Ground-support-equipment operator qualification record"
                              "Safety-management-system record"]}
   "GBR" {:name "United Kingdom"
          :owner-authority "UK Civil Aviation Authority (CAA)"
          :legal-basis "Airports (Groundhandling) Regulations 1997 (SI 1997/2214)"
          :national-spec "UK CAA aerodrome licensing (CAP 168) + ground-handling market-access standards"
          :provenance "https://www.caa.co.uk/our-work/publications/documents/"
          :required-evidence ["Airport-facility-permit record"
                              "Ground-handling-operator authorization record"
                              "Ground-support-equipment operator qualification record"
                              "Safety-management-system record"]}
   "DEU" {:name "Germany"
          :owner-authority "Luftfahrt-Bundesamt (LBA) / state aviation authorities"
          :legal-basis "EU Regulation (EU) No 139/2014 (Aerodromes) / Verordnung über Bodenabfertigungsdienste (BADV)"
          :national-spec "LBA Flugplatzgenehmigungs- und Bodenabfertigungsanforderungen"
          :provenance "https://www.lba.de/DE/Flugplaetze/Flugplaetze_node.html"
          :required-evidence ["Flugplatzgenehmigungsnachweis (airport-facility-permit record)"
                              "Bodenabfertigungsdienstleisterzulassungsnachweis (ground-handling-operator license record)"
                              "Bodenabfertigungsgerätebedienerqualifikationsnachweis (ground-support-equipment operator qualification record)"
                              "Sicherheitsmanagementsystemnachweis (safety-management-system record)"]}})

(defn spec-basis
  "The jurisdiction's requirement map, or nil -- nil means NO spec-basis,
  and the governor must hold any proposal that tries to coordinate
  ground-handling operations on it."
  [iso3]
  (get catalog iso3))

(defn coverage
  "Honest coverage report: how many of the requested jurisdictions
  actually have a spec-basis entry. Never report a missing jurisdiction
  as covered."
  ([] (coverage (keys catalog)))
  ([iso3s]
   (let [have (filter catalog iso3s)
         missing (remove catalog iso3s)]
     {:requested (count iso3s)
      :covered (count have)
      :covered-jurisdictions (vec (sort have))
      :missing-jurisdictions (vec (sort missing))
      :note (str "cloud-itonami-isic-5223 R0: " (count catalog)
                 " jurisdictions seeded with an official spec-basis. "
                 "This is a starting catalog, not a survey of all ~194 "
                 "jurisdictions -- extend `groundops.facts/catalog`, "
                 "never fabricate a jurisdiction's requirements.")})))

(defn required-evidence-satisfied?
  "Does `submitted` (a set/coll of evidence keywords or strings) satisfy
  every evidence item listed for `iso3`? Missing spec-basis -> never
  satisfied."
  [iso3 submitted]
  (when-let [{:keys [required-evidence]} (spec-basis iso3)]
    (let [need (count required-evidence)
          have (count (filter (set submitted) required-evidence))]
      (= need have))))

(defn evidence-checklist [iso3]
  (:required-evidence (spec-basis iso3) []))

(defn citation
  "The [legal-basis provenance] citation pair for `iso3`, or nil when
  there is no spec-basis -- the advisor uses this directly as its own
  `:cites`, it never invents a citation."
  [iso3]
  (when-let [sb (spec-basis iso3)]
    [(:legal-basis sb) (:provenance sb)]))

(defn owner-authority [iso3]
  (:owner-authority (spec-basis iso3)))

(defn known-jurisdiction? [iso3]
  (boolean (and iso3 (not (str/blank? iso3)) (spec-basis iso3))))
