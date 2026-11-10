package io.gutapk.core.offline

import io.gutapk.tools.Json
import java.io.ByteArrayOutputStream
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.ln

// The secrets of a player (a session token, a device id, an advertising id, a
// signature) sit in the request bodies and queries a game recorded, and the
// recordings travel inside the offline APK: whoever unzips a shared APK could
// replay them. The copies that go into the APK are filtered, the PC's own
// recordings stay whole (the learner needs them). Nothing about a game is
// fixed here: a value is a secret because two open source detectors say so.
//
//   1. gitleaks' generic-api-key rule: a keyword in the name of the field
//      (token, key, secret...), a value of its shape, an entropy above 3.5,
//      and its allowlist and stopwords.
//   2. detect-secrets' base64 (limit 4.5) and hex (limit 3.0) plugins: a string
//      of the charset with a high entropy, whatever its name.
//   3. A second pass: a string equal to a value found secret is a secret too,
//      wherever it sits (a device id repeats the token under another name).
//
// Each secret is replaced by a placeholder and its field stays, as VCR.py
// replaces the value of a named parameter. The recorded hash of the request
// body is left in the metadata, so the same request still finds its answer.
//
// https://github.com/gitleaks/gitleaks/blob/master/config/gitleaks.toml#L637-L2128
// https://github.com/gitleaks/gitleaks/blob/master/detect/utils.go#shannonEntropy
// https://github.com/Yelp/detect-secrets/blob/master/detect_secrets/plugins/high_entropy_strings.py#HighEntropyStringsPlugin
// https://github.com/kevin1024/vcrpy/blob/master/vcr/filters.py#replace_post_data_parameters
// not ported: the rules of gitleaks other than generic-api-key (per-service patterns, a game's own token has none)
// not ported: gitleaks' allowlists on a path, a commit or a line (a field has none, the two line patterns are about shell and import statements)
// not ported: gitleaks' decoding passes, the baseline, the report and the redaction
// not ported: NewDetector (one rule is applied by detectRule, there is no detector object), NewDetectorContext (same), NewDetectorDefaultConfig (same)
// not ported: checkCommitOrPathAllowed (a field has no commit and no path), allTrue (no allowlist here has an AND condition), abs (proximity of required rules)
// not ported: __init__ (the constructor of the class), format_scan_result (reporting), json (reporting), non_quoted_string_regex (its exact form is the one regex of the class)
// not ported: analyze_string (ported as analyzeString), analyze_line (ported as analyzeLine), calculate_shannon_entropy (ported as calculateShannonEntropy)
// not ported: _replace_nested_parameters (ported as replaceNestedParameters), replace_query_parameters (ported as replaceQueryParameters), replace_text (ported as replaceText)
// not ported: replace_body_parameters (ported as replaceBodyParameters), replace_post_data_parameters (ported as replacePostDataParameters)
// not ported: detect-secrets' line and quote scanning (a value is tested whole), verify
// not ported: vcrpy's remove_* wrappers, replace_headers (the request headers are not recorded), the urlencoded and multipart branches (a body that is neither BSON nor JSON travels as recorded and is counted)
// Copyright (c) Zachary Rice, gitleaks, MIT. Copyright (c) 2018 Yelp, detect-secrets, Apache-2.0. Copyright (c) 2012 Kevin McCarthy, vcrpy, MIT.
object Secrets {
    // What a secret value becomes. A word, so that every reader of the field gets a string.
    const val PLACEHOLDER = "gutapk-filtered"

    // The line of a metadata file that holds the query (Store.java: origin, method, path, query).
    private const val QUERY_LINE = 3

    // The entropy gitleaks asks of the rule: a finding at or under it is dropped.
    private const val ENTROPY = 3.5

    // The words the rule looks for before running its regex.
    private val KEYWORDS = listOf("access", "api", "auth", "key", "credential", "creds", "passwd", "password", "secret", "token")

    // The regex of generic-api-key, group 1 is the secret. The semicolon of its last class is \x3B
    // (no semicolon in this source), the same character.
    // https://github.com/gitleaks/gitleaks/blob/master/config/gitleaks.toml#L638-L660
    private val GENERIC_API_KEY = Regex(
        """(?i)[\w.-]{0,50}?(?:access|auth|(?-i:[Aa]pi|API)|credential|creds|key|passw(?:or)?d|secret|token)(?:[ \t\w.-]{0,20})[\s'"]{0,3}(?:=|>|:{1,3}=|\|\||:|=>|\?=|,)[\x60'"\s=]{0,5}([\w.=-]{10,150}|[a-z0-9][a-z0-9+/]{11,}={0,3})(?:[\x60'"\s\x3B]|\\[nr]|$)""",
    )

    // The first allowlist: a secret of letters only is a word, not a key (golang's regex has no lookahead).
    private val ALLOWED_SECRET = Regex("""^[a-zA-Z_.-]+$""")

    // The second allowlist, tested on the whole match: names that hold a keyword and are not secrets.
    private val ALLOWED_MATCH = Regex(
        """(?i)(?:access(?:ibility|or)|access[_.-]?id|random[_.-]?access|api[_.-]?(?:id|name|version)|rapid|capital|[a-z0-9-]*?api[a-z0-9-]*?:jar:|author""" +
            """|X-MS-Exchange-Organization-Auth|Authentication-Results|(?:credentials?[_.-]?id|withCredentials)""" +
            """|(?:bucket|foreign|hot|idx|natural|primary|pub(?:lic)?|schema|sequence)[_.-]?key|(?:turkey)""" +
            """|key[_.-]?(?:alias|board|code|frame|id|length|mesh|name|pair|press(?:ed)?|ring|selector|signature|size|stone|storetype|word|up|down|left|right)""" +
            """|key[_.-]?vault[_.-]?(?:id|name)|keyVaultToStoreSecrets|key(?:store|tab)[_.-]?(?:file|path)|issuerkeyhash|(?-i:[DdMm]onkey|[DM]ONKEY)|keying""" +
            """|(?:secret)[_.-]?(?:length|name|size)|UserSecretsId|(?:csrf)[_.-]?token|(?:io\.jsonwebtoken[ \t]?:[ \t]?[\w-]+)""" +
            """|(?:api|credentials|token)[_.-]?(?:endpoint|ur[il])|public[_.-]?token|(?:key|token)[_.-]?file""" +
            """|(?-i:(?:[A-Z_]+=\n[A-Z_]+=|[a-z_]+=\n[a-z_]+=)(?:\n|\z))|(?-i:(?:[A-Z.]+=\n[A-Z.]+=|[a-z.]+=\n[a-z.]+=)(?:\n|\z)))""",
    )

    // The stopwords of the second allowlist: a secret that holds one of them (any case) is a word or a path.
    // https://github.com/gitleaks/gitleaks/blob/master/config/gitleaks.toml#L664-L2111
    private val stopwords: List<String> = listOf(
        "000000 6fe4476ee5a1832882e326b506d14126 _ec2_ aaaaaa about abstract academy acces account act- act. act_ action active actively activity adapter add- add-on add.",
        "add_ addon addres admin adobe advanced adventure agent agile air- air. air_ ajax akka alert alfred algorithm all- all. all_ alloy alpha amazon amqp analysi analytic",
        "analyzer android angular angularj animate animation another ansible answer ant- ant. ant_ any- any. any_ apache app- app. app_ apple arch archive archived arduino",
        "array art- art. art_ article asp- asp. asp_ asset async atom attention audio audit aura auth author authorize auto automated automatic awesome aws_ azure back",
        "backbone backend backup bar- bar. bar_ base based bash basic batch been beer behavior being benchmark best beta better big- big. big_ binary binding bit- bit.",
        "bit_ bitcoin block blog board book bookmark boost boot bootstrap bosh bot- bot. bot_ bower box- box. box_ boxen bracket branch bridge browser brunch buffer bug-",
        "bug. bug_ build builder building buildout buildpack built bundle busines but- but. but_ button cache caching cakephp calendar call camera campfire can- can. can_",
        "canva captcha capture card carousel case cassandra cat- cat. cat_ category center cento challenge change changelog channel chart chat cheat check checker chef ches",
        "chinese chosen chrome ckeditor clas classe classic clean cli- cli. cli_ client clojure clone closure cloud club cluster cms- cms_ coco code coding coffee color",
        "combination combo command commander comment commit common community compas compiler complete component composer computer computing con- con. con_ concept conf config",
        "connect connector console contact container contao content contest context control convert converter conway' cookbook cookie cool copy cordova core couchbase",
        "couchdb countdown counter course craft crawler create creating creator credential crm- crm. crm_ cros crud csv- csv. csv_ cube cucumber cuda current currently",
        "custom daemon dark dart dash dashboard data database date day- day. day_ dead debian debug debugger deck define del- del. del_ delete demo deploy design designer",
        "desktop detection detector dev- dev. dev_ develop developer device devise diff digital directive directory discovery display django dns- dns_ doc- doc. doc_",
        "docker docpad doctrine document doe- doe. doe_ dojo dom- dom. dom_ domain don't done dot- dot. dot_ dotfile download draft drag drill drive driven driver drop",
        "dropbox drupal dsl- dsl. dsl_ dynamic easy ecdsa eclipse edit editing edition editor element emac email embed embedded ember emitter emulator encoding endpoint",
        "engine english enhanced entity entry env_ episode erlang error espresso event evented example exchange exercise experiment expire exploit explorer export exporter",
        "expres ext- ext. ext_ extended extension external extra extractor fabric facebook factory fake fast feature feed fewfwef ffmpeg field file filter find finder",
        "firefox firmware first fish fix- fix_ flash flask flat flex flexible flickr flow fluent fluentd fluid folder font force foreman fork form format formatter forum",
        "foundry framework free friend friendly front-end frontend ftp- ftp. ftp_ fuel full fun- fun. fun_ func future gaia gallery game gateway gem- gem. gem_ gen- gen.",
        "gen_ general generator generic genetic get- get. get_ getenv getting ghost gist git- git. git_ github gitignore gitlab glas gmail gnome gnu- gnu. gnu_ goal golang",
        "gollum good google gpu- gpu. gpu_ gradle grail graph graphic great grid groovy group grunt guard gui- gui. gui_ guide guideline gulp gwt- gwt. gwt_ hack hackathon",
        "hacker hacking hadoop haml handler hardware has- has_ hash haskell have haxe hello help helper here hero heroku high hipchat history home homebrew homepage hook",
        "host hosting hot- hot. hot_ house how- how. how_ html http hub- hub. hub_ hubot human icon ide- ide. ide_ idea identity idiomatic image impact import important",
        "importer impres index infinite info injection inline input inside inspector instagram install installer instant intellij interface internet interview into intro",
        "ionic iphone ipython irc- irc_ iso- iso. iso_ issue jade jasmine java jbos jekyll jenkin jetbrains job- job. job_ joomla jpa- jpa. jpa_ jquery json just kafka",
        "karma kata kernel keyboard kindle kit- kit. kit_ kitchen knife koan kohana lab- lab. lab_ lambda lamp language laravel last latest latex launcher layer layout",
        "lazy ldap leaflet league learn learning led- led. led_ leetcode les- les. les_ level leveldb lib- lib. lib_ librarie library license life liferay light lightbox",
        "like line link linked linkedin linux lisp list lite little load loader local location lock log- log. log_ logger logging logic login logstash longer look love",
        "lua- lua. lua_ mac- mac. mac_ machine made magento magic mail make maker making man- man. man_ manage manager manifest manual map- map. map_ mapper mapping markdown",
        "markup master math matrix maven md5 mean media mediawiki meetup memcached memory menu merchant message messaging meta metadata meteor method metric micro",
        "middleman migration minecraft miner mini minimal mirror mit- mit. mit_ mobile mocha mock mod- mod. mod_ mode model modern modular module modx money mongo mongodb",
        "mongoid mongoose monitor monkey more motion moved movie mozilla mqtt mule multi multiple music mustache mvc- mvc. mvc_ mysql nagio name native need neo- neo. neo_",
        "nest nested net- net. net_ nette network new- new. new_ next nginx ninja nlp- nlp. nlp_ node nodej nosql not- not. not_ note notebook notepad notice notifier now-",
        "now. now_ number oauth object objective obsolete ocaml octopres official old- old. old_ onboard online only open opencv opengl openshift openwrt option oracle org-",
        "org. org_ origin original orm- orm. orm_ osx- osx_ our- our. our_ out- out. out_ output over overview own- own. own_ pack package packet page panel paper paperclip",
        "para parallax parallel parse parser parsing particle party password patch path pattern payment paypal pdf- pdf. pdf_ pebble people perl personal phalcon phoenix",
        "phone phonegap photo php- php. php_ physic picker pipeline platform play player please plu- plu. plu_ plug-in plugin plupload png- png. png_ poker polyfill polymer",
        "pool pop- pop. pop_ popcorn popup port portable portal portfolio post power powered powerful prelude pretty preview principle print pro- pro. pro_ problem proc",
        "product profile profiler program progres project protocol prototype provider proxy public pull puppet pure purpose push pusher pyramid python quality query queue",
        "quick rabbitmq rack radio rail railscast random range raspberry rdf- rdf. rdf_ react reactive read reader readme ready real real-time reality realtime recipe",
        "recorder red- red. red_ reddit redi redmine reference refinery refresh registry related release remote rendering repo report request require required requirej",
        "research resource response resque rest restful resume reveal reverse review riak rich right ring robot role room router routing rpc- rpc. rpc_ rpg- rpg. rpg_",
        "rspec ruby- ruby. ruby_ rule run- run. run_ runner running runtime rust rvm- rvm. rvm_ salt sample sandbox sas- sas. sas_ sbt- sbt. sbt_ scala scalable scanner",
        "schema scheme school science scraper scratch screen script scroll scs- scs. scs_ sdk- sdk. sdk_ sdl- sdl. sdl_ search secure security see- see. see_ seed select",
        "selector selenium semantic sencha send sentiment serie server service session set- set. set_ setting setup sha1 sha2 sha256 share shared sharing sheet shell",
        "shield shipping shop shopify shortener should show showcase side silex simple simulator single site skeleton sketch skin slack slide slider slim small smart smtp",
        "snake snapshot snippet soap social socket software solarized solr solution solver some soon source space spark spatial spec sphinx spine spotify spree spring",
        "sprite sql- sql. sql_ sqlite ssh- ssh. ssh_ stack staging standard stanford start started starter startup stat statamic state static statistic statsd statu steam",
        "step still stm- stm. stm_ storage store storm story strategy stream streaming string stripe structure studio study stuff style sublime sugar suite summary super",
        "support supported svg- svg. svg_ svn- svn. svn_ swagger swift switch switcher symfony symphony sync synopsi syntax system tab- tab. tab_ table tag- tag. tag_ talk",
        "target task tcp- tcp. tcp_ tdd- tdd. tdd_ team tech template term terminal testing tetri text textmate theme theory three thrift time timeline timer tiny tinymce",
        "tip- tip. tip_ title todo todomvc token tool toolbox toolkit top- top. top_ tornado touch tower tracker tracking traffic training transfer translate transport tree",
        "trello try- try. try_ tumblr tut- tut. tut_ tutorial tweet twig twitter type typo ubuntu uiview ultimate under unit unity universal unix update updated upgrade",
        "upload uploader uri- uri. uri_ url- url. url_ usage usb- usb. usb_ use- use. use_ used useful user using util utilitie utility vagrant validator value variou",
        "varnish version via- via. via_ video view viewer vim- vim. vim_ vimrc virtual vision visual vpn want warning watch watcher wave way- way. way_ weather web- web_",
        "webapp webgl webhook webkit webrtc website websocket welcome what what' when where which why- why. why_ widget wifi wiki win- win. win_ window wip- wip. wip_",
        "within without wizard word wordpres work worker workflow working workshop world wrapper write writer writing written www- www. www_ xamarin xcode xml- xml. xml_",
        "xmpp xxxxxx yahoo yaml yandex yeoman yet- yet. yet_ yii- yii. yii_ youtube yui- yui. yui_ zend zero zip- zip. zip_ zsh- zsh. zsh_",
    ).flatMap { it.split(' ') }

    // https://github.com/gitleaks/gitleaks/blob/master/detect/utils.go#shannonEntropy
    // The entropy of data, in bits per character. Go's len(data) counts bytes, so does invLength here.
    fun shannonEntropy(data: String): Double {
        if (data.isEmpty()) return 0.0
        val charCounts = HashMap<Int, Int>()
        data.codePoints().forEach { char -> charCounts.merge(char, 1, Int::plus) }
        val invLength = 1.0 / data.toByteArray(Charsets.UTF_8).size
        var entropy = 0.0
        for (count in charCounts.values) {
            val freq = count * invLength
            entropy -= freq * (ln(freq) / ln(2.0))
        }
        return entropy
    }

    // https://github.com/gitleaks/gitleaks/blob/master/config/allowlist.go#ContainsStopWord
    // not ported: the Aho-Corasick trie (a plain search finds the same word, the list is short)
    private fun containsStopWord(secret: String): Boolean {
        if (secret.isEmpty()) return false
        val lowered = secret.lowercase()
        return stopwords.any { stopWord -> lowered.contains(stopWord) }
    }

    // https://github.com/gitleaks/gitleaks/blob/master/detect/detect.go#checkFindingAllowed
    // The two allowlists that apply to a field, the first tested on the secret and the second on the match.
    private fun checkFindingAllowed(secret: String, match: String): Boolean {
        val regexAllowed = ALLOWED_SECRET.containsMatchIn(secret)
        if (regexAllowed) return true
        return ALLOWED_MATCH.containsMatchIn(match) || containsStopWord(secret)
    }

    // https://github.com/gitleaks/gitleaks/blob/master/detect/detect.go#detectRule
    // The line gitleaks reads is the pair written as a config line, name: "value".
    // True when the rule finds a secret in it.
    fun detectRule(name: String, value: String): Boolean {
        val currentRaw = "$name: \"$value\""
        val normalizedRaw = currentRaw.lowercase()
        if (KEYWORDS.none { normalizedRaw.contains(it) }) return false
        for (found in GENERIC_API_KEY.findAll(currentRaw)) {
            val match = found.value.trim('\n')
            val secret = found.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: match
            // an entropy at or under the rule's is too low
            if (shannonEntropy(secret) <= ENTROPY) continue
            if (checkFindingAllowed(secret, match)) continue
            return true
        }
        return false
    }

    // https://github.com/Yelp/detect-secrets/blob/master/detect_secrets/plugins/high_entropy_strings.py#HighEntropyStringsPlugin
    // Base for the strings of a charset whose entropy is above a limit.
    // not ported: BasePlugin and PotentialSecret (a hit here is a yes), the eager search of adhoc scans
    open class HighEntropyStringsPlugin(val charset: String, limit: Double) {
        val entropyLimit: Double = limit

        init {
            if (limit < 0 || limit > 8) throw IllegalArgumentException("The limit set for HighEntropyStrings must be between 0.0 and 8.0")
        }

        // A value is one string, so the unquoted form of the regex (non_quoted_string_regex with
        // is_exact_match) is the one that applies: the whole string made of the charset.
        private val regex = Regex("^([" + charset.map { if (it.isLetterOrDigit()) "$it" else "\\$it" }.joinToString("") + "]+)$")

        fun analyzeString(string: String): List<String> = regex.findAll(string).map { it.groupValues[1] }.toList()

        // The entropy limit is checked here, as the original does at this layer.
        fun analyzeLine(line: String): Set<String> =
            analyzeString(line).filter { calculateShannonEntropy(it) > entropyLimit }.toSet()

        // Borrowed by the original from the blog of dkbza (2007, scanning data for entropy anomalies).
        open fun calculateShannonEntropy(data: String): Double {
            if (data.isEmpty()) return 0.0
            var entropy = 0.0
            for (x in charset) {
                val pX = data.count { it == x }.toDouble() / data.length
                if (pX > 0) entropy += -pX * (ln(pX) / ln(2.0))
            }
            return entropy
        }
    }

    // Python's string.ascii_letters, string.digits and string.hexdigits, the charsets of the plugins.
    private const val ASCII_LETTERS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private const val DIGITS = "0123456789"
    private const val HEXDIGITS = "0123456789abcdefABCDEF"

    // https://github.com/Yelp/detect-secrets/blob/master/detect_secrets/plugins/high_entropy_strings.py#Base64HighEntropyString
    // Random-looking base64 strings: regular, url-safe, padding.
    class Base64HighEntropyString(limit: Double = 4.5) : HighEntropyStringsPlugin(
        ASCII_LETTERS + DIGITS + "+/" + "\\-_" + "=",
        limit,
    )

    // https://github.com/Yelp/detect-secrets/blob/master/detect_secrets/plugins/high_entropy_strings.py#HexHighEntropyString
    class HexHighEntropyString(limit: Double = 3.0) : HighEntropyStringsPlugin(HEXDIGITS, limit) {
        // All digits give far more false positives than real secrets, so their entropy is
        // lowered below the limit, less so for a longer string.
        override fun calculateShannonEntropy(data: String): Double {
            var entropy = super.calculateShannonEntropy(data)
            if (data.length == 1) return entropy
            // Python's int(data) of a string made of hex digits only parses when all are decimal
            if (data.isNotEmpty() && data.all { it in '0'..'9' }) {
                // This multiplier was determined through trial and error, by the original's authors.
                entropy -= 1.2 / (ln(data.length.toDouble()) / ln(2.0))
            }
            return entropy
        }
    }

    private val plugins = listOf(Base64HighEntropyString(), HexHighEntropyString())

    // A value is a secret when one of the two detectors says so: gitleaks by the name of its
    // field, detect-secrets by the value alone.
    fun isSecret(name: String, value: String): Boolean =
        detectRule(name, value) || plugins.any { it.analyzeLine(value).isNotEmpty() }

    // https://github.com/kevin1024/vcrpy/blob/master/vcr/filters.py#_replace_nested_parameters
    // A copy of data with every string handed to replacements, at any depth, in lists too. A list
    // item has the name of the field that holds the list. The field stays, a replacement is
    // always a string.
    // not ported: a replacement of None, which removes the field (the field is kept with the placeholder)
    fun replaceNestedParameters(data: Any?, key: String, replacements: (String, String) -> String): Any? = when (data) {
        is Map<*, *> -> {
            val newData = LinkedHashMap<String, Any?>()
            for ((k, ov) in data) newData[k as String] = replaceNestedParameters(ov, k, replacements)
            newData
        }
        is List<*> -> data.map { item -> replaceNestedParameters(item, key, replacements) }
        is String -> replacements(key, data)
        else -> data
    }

    // https://github.com/kevin1024/vcrpy/blob/master/vcr/filters.py#replace_query_parameters
    // The query with the value of each pair handed to replacements. A pair it leaves as it is
    // keeps its text, so a query with nothing secret is not re-encoded.
    fun replaceQueryParameters(query: String, replacements: (String, String) -> String): String =
        query.split('&').joinToString("&") { pair ->
            val separator = pair.indexOf('=')
            if (separator < 0) return@joinToString pair
            runCatching {
                val k = URLDecoder.decode(pair.substring(0, separator), Charsets.UTF_8)
                val ov = URLDecoder.decode(pair.substring(separator + 1), Charsets.UTF_8)
                val rv = replacements(k, ov)
                if (rv == ov) pair else pair.substring(0, separator + 1) + URLEncoder.encode(rv, Charsets.UTF_8)
            }.getOrDefault(pair)
        }

    // https://github.com/kevin1024/vcrpy/blob/master/vcr/filters.py#replace_text
    // The bytes with every occurrence of text replaced. A JSON body is rewritten this way:
    // the reader (tools/Json.kt) has no writer, and the text of a body is kept as it was.
    // not ported: the str, BytesIO, dict and list branches (a body is bytes here)
    fun replaceText(obj: ByteArray, text: String, replacement: String): ByteArray {
        val from = text.toByteArray(Charsets.UTF_8)
        val to = replacement.toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < obj.size) {
            if (from.isNotEmpty() && i + from.size <= obj.size && from.indices.all { obj[i + it] == from[it] }) {
                out.write(to)
                i += from.size
            } else {
                out.write(obj[i].toInt())
                i++
            }
        }
        return out.toByteArray()
    }

    // https://github.com/kevin1024/vcrpy/blob/master/vcr/filters.py#replace_body_parameters
    // The body parameters of a decoded request: BSON documents. Recursive, as the original's
    // recursive mode (the documents of a game nest nine levels).
    // not ported: _replace_urlencoded_parameters, _multipart_boundary, _replace_multipart_parameters (see the top)
    @Suppress("UNCHECKED_CAST")
    fun replaceBodyParameters(documents: List<Map<String, Any?>>, replacements: (String, String) -> String): List<Map<String, Any?>> =
        documents.map { replaceNestedParameters(it, "", replacements) as Map<String, Any?> }

    // https://github.com/kevin1024/vcrpy/blob/master/vcr/filters.py#replace_post_data_parameters
    // not ported: the test of the method (the body of a PUT can hold a credential too), the request object
    fun replacePostDataParameters(documents: List<Map<String, Any?>>, replacements: (String, String) -> String): List<Map<String, Any?>> =
        replaceBodyParameters(documents, replacements)

    // What a request is made of, as found in the recordings folder.
    private class Request(
        val metadata: Path,
        val lines: List<String>?,
        val body: ByteArray?,
        val documents: List<Map<String, Any?>>?,
        val json: Any?,
    )

    // files: the folder's files in the names the APK keeps, into: where the rewritten ones are
    // written. values: distinct secret values, recordings: how many files had one replaced,
    // left: request bodies that are neither BSON nor JSON and travel as recorded.
    class Filtered(val files: List<Path>, val values: Int, val recordings: Int, val left: Int)

    fun filter(files: List<Path>, into: Path): Filtered {
        val found = LinkedHashSet<String>()
        val requests = ArrayList<Request>()
        var left = 0
        // First pass: both detectors on every query and every body, nothing is changed.
        val seek = { name: String, value: String ->
            if (isSecret(name, value)) found.add(value)
            value
        }
        for (metadata in files) {
            val file = metadata.fileName.toString()
            if (!file.endsWith(OfflineBuild.ENTRY)) continue
            val lines = runCatching { Files.readString(metadata).split('\n') }.getOrNull()
            lines?.getOrNull(QUERY_LINE)?.let { replaceQueryParameters(it, seek) }
            val bodyFile = metadata.resolveSibling(file.removeSuffix(OfflineBuild.ENTRY) + ".2")
            val body = if (Files.isRegularFile(bodyFile)) Files.readAllBytes(bodyFile) else null
            val documents = body?.takeIf { it.isNotEmpty() }?.let { Bson.documents(it) }?.takeIf { it.isNotEmpty() }
            val json = if (documents == null && body != null && body.isNotEmpty()) {
                runCatching { Json.parse(String(body, Charsets.UTF_8)) }.getOrNull()?.takeIf { it is Map<*, *> || it is List<*> }
            } else {
                null
            }
            when {
                documents != null -> replacePostDataParameters(documents, seek)
                json != null -> replaceNestedParameters(json, "", seek)
                body != null && body.isNotEmpty() -> left++
            }
            requests.add(Request(metadata, lines, body, documents, json))
        }
        // Second pass: every string equal to a secret found anywhere is replaced, which
        // catches the same value under a name no detector reads (a device id equal to the token).
        var recordings = 0
        val rewritten = HashMap<Path, Path>()
        Files.createDirectories(into)
        for (request in requests) {
            var changed = false
            val hide = { _: String, value: String ->
                if (value in found) {
                    changed = true
                    PLACEHOLDER
                } else {
                    value
                }
            }
            val lines = request.lines
            if (lines != null && lines.size > QUERY_LINE) {
                val query = replaceQueryParameters(lines[QUERY_LINE], hide)
                if (changed) {
                    val metadata = into.resolve(request.metadata.fileName)
                    Files.writeString(metadata, lines.toMutableList().also { it[QUERY_LINE] = query }.joinToString("\n"))
                    rewritten[request.metadata] = metadata
                }
            }
            val queryChanged = changed
            changed = false
            val bodyFile = request.metadata.resolveSibling(request.metadata.fileName.toString().removeSuffix(OfflineBuild.ENTRY) + ".2")
            if (request.documents != null) {
                val documents = replacePostDataParameters(request.documents, hide)
                // The length fields are written again by the encoder, the original's total length is not kept.
                if (changed) rewritten[bodyFile] = Files.write(into.resolve(bodyFile.fileName), encode(documents))
            } else if (request.json != null && request.body != null) {
                // Only the values found are searched in the text, longest first so that a value
                // holding another is replaced whole.
                var text: ByteArray = request.body
                for (value in found.sortedByDescending { it.length }) text = replaceText(text, value, PLACEHOLDER)
                changed = !text.contentEquals(request.body)
                if (changed) rewritten[bodyFile] = Files.write(into.resolve(bodyFile.fileName), text)
            }
            if (queryChanged || changed) recordings++
        }
        return Filtered(files.map { rewritten[it] ?: it }, found.size, recordings, left)
    }

    // The documents one after the other, as a body held them.
    private fun encode(documents: List<Map<String, Any?>>): ByteArray {
        val out = ByteArrayOutputStream()
        documents.forEach { Bson.encodeDocument(it, out) }
        return out.toByteArray()
    }
}
