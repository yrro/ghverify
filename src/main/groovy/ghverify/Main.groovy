package ghverify

import groovyx.net.http.*
import static groovyx.net.http.Method.GET
import static groovyx.net.http.ContentType.JSON

import org.joda.time.*
import org.joda.time.format.*

class Main {
    static def parseDate(String datetime) {
        def fmt = ISODateTimeFormat.dateTimeNoMillis()
        def dt = fmt.parseDateTime(datetime)

        def seconds = dt.getMillis()/1000
        // will use the default TZ, hopefully this is what the tag was created with!
        def offset = DateTimeFormat.forPattern('Z').print(dt)
        return [seconds, offset]
    }

    static def gpgv(File sig, File msg) {
        // Java 7 version
        /*
        def proc = new ProcessBuilder()
            .inheritIO()
            .command('cat', sig.getAbsolutePath(), msg.getAbsolutePath())
            .start()
        */

        // Java 6 version
        def proc = Runtime.getRuntime()
            .exec("gpg --verify ${sig.getAbsolutePath()} ${msg.getAbsolutePath()}")
        def t1 = Thread.start {
            for (Scanner s = new Scanner(proc.getInputStream()); s.hasNextLine();) {
                System.out.println s.nextLine()
            }
        }
        def t2 = Thread.start {
            for (Scanner s = new Scanner(proc.getErrorStream()); s.hasNextLine();) {
                System.err.println s.nextLine()
            }
        }
        t1.join()
        t2.join()

        return proc.waitFor()
    }

    static void main(String[] args) {
        if(args.size() != 4) {
            System.err.println 'Usage: ghverify apikey owner repo tag'
            System.exit 1
        }
        System.exit(new Main().run(args[0], args[1], args[2], args[3]))
    }

    int run(String apikey, String owner, String repo, String tag) {
        def http = new HTTPBuilder('https://api.github.com')
        http.headers.'Authorization' = "token $apikey"
        http.headers.'User-agent' = 'ghverify'

        def ref_json
        try {
            http.get(path:"/repos/$owner/$repo/git/refs/tags/$tag") { resp, json ->
                ref_json = json
            }
        } catch (HttpResponseException e) {
            if(e.getStatusCode() == 404) {
                System.err.println "Tag ${tag} not found in ${owner}/${repo}"
                return 1
            }
            throw e;
        }

        def tag_json
        http.get(path:"/repos/${owner}/${repo}/git/tags/${ref_json.object.sha}") { resp, json ->
            tag_json = json
        }

        def sig_index = tag_json.message.indexOf('-----BEGIN PGP SIGNATURE-----')
        if(sig_index == -1) {
            System.err.println "Tag ${tag} is not a signed tag"
            return 1
        }

        def (seconds, offset) = parseDate(tag_json.tagger.date)
        def msg_file = File.createTempFile('ghverify-', '.txt')
        msg_file.deleteOnExit()
        msg_file << "object ${tag_json.object.sha}\n"
        msg_file << "type commit\n"
        msg_file << "tag ${tag}\n"
        msg_file << "tagger ${tag_json.tagger.name} <${tag_json.tagger.email}> ${seconds} ${offset}\n"
        msg_file << '\n'
        msg_file << tag_json.message[0..sig_index-1]

        def sig_file = File.createTempFile('ghverify-', '.txt')
        sig_file.deleteOnExit()
        sig_file << tag_json.message

        return gpgv(sig_file, msg_file)
    }
}
