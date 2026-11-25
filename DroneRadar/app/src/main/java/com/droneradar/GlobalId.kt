package com.droneradar

/**
 * Remote ID identity checks that work in every market.
 *
 *  Operator ID (EU / UK / EASA states, ASD-STAN EN 4709-002 — also used by other CAAs):
 *    16 chars = ISO 3166 alpha-3 country (upper case) + 12 public chars + 1 checksum char.
 *    The checksum (Luhn mod 36) covers 3 secret chars that are NOT broadcast, so a receiver can
 *    check the structure and the country, not the checksum — unless the secret was leaked into
 *    the broadcast ("FIN87astrdge12k8-xyz"), which we then verify and report.
 *  US: FAA registration numbers ("FA" + 8). Operator ID is optional in the US.
 *  Basic ID type 2 (CAA registration): Japan "JU" + 10.
 *  Serial numbers everywhere: ANSI/CTA-2063 (SpoofCheck.cta2063Ok) — first 4 chars = manufacturer.
 */
object GlobalId {
    private val ISO3: Map<String, String> = "ABWAW AFGAF AGOAO AIAAI ALAAX ALBAL ANDAD AREAE ARGAR ARMAM ASMAS ATAAQ ATFTF ATGAG AUSAU AUTAT AZEAZ BDIBI BELBE BENBJ BESBQ BFABF BGDBD BGRBG BHRBH BHSBS BIHBA BLMBL BLRBY BLZBZ BMUBM BOLBO BRABR BRBBB BRNBN BTNBT BVTBV BWABW CAFCF CANCA CCKCC CHECH CHLCL CHNCN CIVCI CMRCM CODCD COGCG COKCK COLCO COMKM CPVCV CRICR CUBCU CUWCW CXRCX CYMKY CYPCY CZECZ DEUDE DJIDJ DMADM DNKDK DOMDO DZADZ ECUEC EGYEG ERIER ESHEH ESPES ESTEE ETHET FINFI FJIFJ FLKFK FRAFR FROFO FSMFM GABGA GBRGB GEOGE GGYGG GHAGH GIBGI GINGN GLPGP GMBGM GNBGW GNQGQ GRCGR GRDGD GRLGL GTMGT GUFGF GUMGU GUYGY HKGHK HMDHM HNDHN HRVHR HTIHT HUNHU IDNID IMNIM INDIN IOTIO IRLIE IRNIR IRQIQ ISLIS ISRIL ITAIT JAMJM JEYJE JORJO JPNJP KAZKZ KENKE KGZKG KHMKH KIRKI KNAKN KORKR KWTKW LAOLA LBNLB LBRLR LBYLY LCALC LIELI LKALK LSOLS LTULT LUXLU LVALV MACMO MAFMF MARMA MCOMC MDAMD MDGMG MDVMV MEXMX MHLMH MKDMK MLIML MLTMT MMRMM MNEME MNGMN MNPMP MOZMZ MRTMR MSRMS MTQMQ MUSMU MWIMW MYSMY MYTYT NAMNA NCLNC NERNE NFKNF NGANG NICNI NIUNU NLDNL NORNO NPLNP NRUNR NZLNZ OMNOM PAKPK PANPA PCNPN PERPE PHLPH PLWPW PNGPG POLPL PRIPR PRKKP PRTPT PRYPY PSEPS PYFPF QATQA REURE ROURO RUSRU RWARW SAUSA SDNSD SENSN SGPSG SGSGS SHNSH SJMSJ SLBSB SLESL SLVSV SMRSM SOMSO SPMPM SRBRS SSDSS STPST SURSR SVKSK SVNSI SWESE SWZSZ SXMSX SYCSC SYRSY TCATC TCDTD TGOTG THATH TJKTJ TKLTK TKMTM TLSTL TONTO TTOTT TUNTN TURTR TUVTV TWNTW TZATZ UGAUG UKRUA UMIUM URYUY USAUS UZBUZ VATVA VCTVC VENVE VGBVG VIRVI VNMVN VUTVU WLFWF WSMWS YEMYE ZAFZA ZMBZM ZWEZW".split(' ').associate { it.substring(0, 3) to it.substring(3) }

    /** Countries that issue EN 4709-002 operator numbers (EASA members + UK). */
    private val EN4709 = setOf("AUT", "BEL", "BGR", "HRV", "CYP", "CZE", "DNK", "EST", "FIN", "FRA", "DEU", "GRC", "HUN", "IRL", "ITA", "LVA", "LTU", "LUX", "MLT", "NLD", "POL", "PRT", "ROU", "SVK", "SVN", "ESP", "SWE", "NOR", "ISL", "LIE", "CHE", "GBR")

    /** Manufacturer codes (CTA-2063 MFR code) we are sure about. */
    private val MFR = mapOf("1581" to "DJI")

    data class Info(val country: String?, val flag: String?, val scheme: String, val ok: Boolean?, val note: String? = null)

    private const val A36 = "0123456789abcdefghijklmnopqrstuvwxyz"

    fun flag(alpha2: String?): String? = alpha2?.takeIf { it.length == 2 }?.uppercase()?.let { a ->
        String(Character.toChars(0x1F1E6 + (a[0] - 'A'))) + String(Character.toChars(0x1F1E6 + (a[1] - 'A')))
    }

    private fun country3(c: String): Pair<String, String?>? = ISO3[c]?.let { c to flag(it) }

    /** Luhn mod 36 check character over [s] (lower-case base-36 chars); null = not computable. */
    fun luhn36(s: String): Char? {
        var factor = 2; var total = 0
        for (ch in s.reversed()) {
            val cp = A36.indexOf(ch); if (cp < 0) return null
            var add = factor * cp
            factor = if (factor == 2) 1 else 2
            add = add / 36 + add % 36
            total += add
        }
        return A36[(36 - total % 36) % 36]
    }

    private val EU16 = Regex("^([A-Z]{3})([0-9a-z]{12})([0-9a-z])$")
    /** With the 3 secret chars (should never be broadcast). The dash is optional: 19 or 20 chars. */
    private val EU20 = Regex("^([A-Z]{3})([0-9a-z]{12})([0-9a-z])-?([0-9a-z]{3})$")
    private val FAA = Regex("^FA[0-9A-Z]{8}$")
    private val JU = Regex("^JU[0-9A-Z]{10}$")

    /** null = nothing to say (empty / unknown national format). */
    fun operator(raw: String?): Info? {
        var id = raw?.trim()?.trimEnd('\u0000')?.takeIf { it.isNotEmpty() } ?: return null
        // The standard's way of saying "no operator ID": not a format to judge.
        if (id.equals("NULL", ignoreCase = true)) return Info(null, null, "undeclared", null)
        // some apps upper-case the whole number; the standard is COUNTRY + lower-case base-36
        if ((id.length in 16..20) && id.take(3).all { it.isLetter() })
            id = id.take(3).uppercase() + id.drop(3).lowercase()
        EU20.matchEntire(id)?.let { m ->
            val (c, base, chk, secret) = m.destructured
            val ctry = country3(c)
            val sum = luhn36(base + secret)
            // ok: false = computed and wrong (or no such country); null = could not be computed
            val good = if (ctry == null) false else sum?.let { it == chk[0] }
            return Info(c, ctry?.second, "EN4709", good, "secret_broadcast")
        }
        EU16.matchEntire(id)?.let { m ->
            val ctry = country3(m.groupValues[1])
            return Info(m.groupValues[1], ctry?.second, "EN4709", ctry != null)
        }
        if (FAA.matches(id.uppercase())) return Info("USA", flag("US"), "FAA", true)
        // Looks like an EU-style number (3-letter country first) but the structure is wrong.
        val head = id.take(3)
        if (id.length in 10..24 && head in EN4709)
            return Info(head, flag(ISO3[head]), "EN4709", false, "bad_format")
        return Info(null, null, "national", null)
    }

    /** Basic ID: who made it / where it is registered. */
    fun uas(idType: Int?, raw: String?): Info? {
        val id = raw?.trim()?.trimEnd('\u0000')?.takeIf { it.isNotEmpty() } ?: return null
        return when (idType) {
            1 -> Info(null, null, "CTA-2063", SpoofCheck.cta2063Ok(id), MFR[id.take(4).uppercase()])
            2 -> if (id.uppercase().startsWith("JU")) Info("JPN", flag("JP"), "JCAB", JU.matches(id.uppercase()))
                 else Info(null, null, "CAA", null)
            3 -> Info(null, null, "UTM-UUID", null)
            4 -> Info(null, null, "session", null)
            else -> null
        }
    }

    /** Short line for the drone card: "🇫🇷 FRA • DJI". */
    fun label(d: DroneInfo): String? {
        val op = operator(d.operatorId)
        val ua = uas(d.idType, d.serial)
        val parts = listOfNotNull(
            op?.country?.let { listOfNotNull(op.flag, it).joinToString(" ") } ?: ua?.country?.let { listOfNotNull(ua.flag, it).joinToString(" ") },
            ua?.note,
        )
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" • ")
    }

    /**
     * THE one place that decides whether a drone's identity numbers are malformed.
     * Returns the weak forgery flags: "bad_serial" (CTA-2063 / CAA registration) and "bad_operator_id".
     */
    fun idFlags(d: DroneInfo): Set<String> {
        val f = mutableSetOf<String>()
        val ua = uas(d.idType, d.serial)
        if ((d.idType == 1 || d.idType == 2) && ua?.ok == false) f += "bad_serial"
        if (operator(d.operatorId)?.ok == false) f += "bad_operator_id"
        return f
    }
    fun badId(d: DroneInfo): Boolean = idFlags(d).isNotEmpty()

    fun secretLeaked(d: DroneInfo): Boolean = operator(d.operatorId)?.note == "secret_broadcast"
}
