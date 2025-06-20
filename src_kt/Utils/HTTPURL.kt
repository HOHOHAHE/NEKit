import java.util.regex.PatternSyntaxException

class HTTPURL private constructor(
    val scheme: String?,
    val host: String?,
    val port: Int?,
    val relativePath: String
) {
    companion object {
        // Original Swift regex: "^(?:(?:(https?):\\/\\/)?([\\w\\.]+)(?::(\\d+))?)?(?:\\/(.*))?$"
        // Breakdown:
        // ^                                      # Start of the string
        // (?:                                    # Non-capturing group for the whole scheme/host/port part (optional)
        //   (?:                                  # Non-capturing group for scheme and "//"
        //     (https?)                           # Capturing group 1: "http" or "https" (scheme)
        //     :\\/\\/                             # Literal "://"
        //   )?                                   # End of scheme part, make it optional
        //   ([\\w\\.]+)                          # Capturing group 2: Hostname (word characters and dots)
        //   (?::(\\d+))?                         # Optional non-capturing group for port:
        //                                        #   ":" literal
        //                                        #   (\\d+) Capturing group 3: Port (digits)
        // )?                                     # End of the scheme/host/port part, make it optional
        // (?:\\/(.*))?                           # Optional non-capturing group for path:
        //                                        #   "/" literal
        //                                        #   (.*) Capturing group 4: Path (anything after /)
        // $                                      # End of the string

        // NSRegularExpression.Options.caseInsensitive -> RegexOption.IGNORE_CASE
        // The Swift code force-unwraps the regex compilation (try!). Kotlin Regex constructor throws on syntax error.
        private val URL_REGEX: Regex = try {
            Regex("^(?:(?:(https?):\\/\\/)?([\\w\\.-]+)(?::(\\d+))?)?(?:\\/(.*))?\$", RegexOption.IGNORE_CASE)
            // Note: Replaced \\. with \\.- in host matching to allow for hostnames like "foo-bar.com"
            // Original Swift regex [\\w\\.]+ might imply word characters OR dots, not both together always.
            // \\w typically includes A-Za-z0-9_
            // If hostnames can start/end with dots or have multiple dots, this is fine.
            // Standard hostnames don't start/end with hyphens or dots, but can contain hyphens.
        } catch (e: PatternSyntaxException) {
            // This would be a critical initialization error, similar to Swift's try! failing
            throw IllegalStateException("Failed to compile URL regex", e)
        }

        fun parse(url: String): HTTPURL? {
            val matchResult = URL_REGEX.find(url) ?: return null

            // Group 0 is the whole match. Groups 1-4 are the captured parts.
            // Swift: result.numberOfRanges == 5 (includes group 0)
            // Kotlin: matchResult.groups.size will be 5 if all capture groups are defined in regex.

            val scheme = matchResult.groups[1]?.value
            val host = matchResult.groups[2]?.value
            val portString = matchResult.groups[3]?.value
            val relativePathPart = matchResult.groups[4]?.value

            val port = portString?.toIntOrNull()

            // The original Swift code implies that if the entire scheme/host/port part is missing,
            // host and port would be nil. If only path is present (e.g., "/index.html"),
            // then scheme, host, port are nil.
            // If the regex matches but group 2 (host) is not found, it means it's likely a path-only URL.
            // However, the regex structure makes the host group (group 2) mandatory if the
            // scheme/host/port part of the regex matches.
            // A URL like "/path/to/resource" would match, with groups 1,2,3 being null, and group 4 being "path/to/resource".
            // A URL like "example.com/path" would match, group 1 (scheme) null, group 2 "example.com", group 3 (port) null, group 4 "path".

            // If there's no host, it's not a valid HTTP/S URL in the typical sense for this parser.
            // The Swift code allows host to be nil if not found.
            // Let's ensure that if host is nil, scheme and port are also treated accordingly,
            // or adjust based on what a "valid" parse means for this class.
            // The regex makes the host group ([\\w\\.]+) non-optional if the first optional group matches.
            // So if group 2 is null, it means the input was probably just a path like "/foo/bar"
            // or the input string was empty or did not match the structure at all.
            // If URL_REGEX.find(url) is not null, it means a match occurred.

            // If host is null but relativePathPart is also null, it implies an empty or non-matching string,
            // which should have been handled by `matchResult == null`.
            // If host is null but relativePathPart is not, it's a path-only string like "/foo".
            // In this case, scheme and port should also be null.

            val relativePath = relativePathPart ?: (if (scheme == null && host == null && port == null) "" else "")
            // The Swift code defaults relativePath to "" if group 4 is not found.
            // However, if the input is "http://example.com", group 4 is not matched, so relativePath should be "".
            // If input is "http://example.com/", group 4 is an empty string.
            // My regex `(?:\\/(.*))?` means group 4 can be null if there's no trailing slash and path.
            // If group 4 is null, it means no path part after host (e.g. "http://host.com"). Path should be empty string.
            // If group 4 is an empty string, it means "http://host.com/". Path should be empty string.

            // If the input was like "http://example.com", matchResult.groups[4] would be null.
            // We need to ensure relativePath is "" in this case.
            // If input was "/path", scheme, host, port are null. relativePath is "path".

            return HTTPURL(scheme, host, port, relativePathPart ?: "")
        }
    }

    // Optional: Add a descriptive toString or other utility methods if needed
    override fun toString(): String {
        val portString = port?.let { ":$it" } ?: ""
        val schemeString = scheme?.let { "$it://" } ?: ""
        val hostString = host ?: ""
        // Avoid double slashes if relativePath already starts with one, or if host is empty.
        val pathPrefix = if (hostString.isNotEmpty() && relativePath.isNotEmpty() && !relativePath.startsWith("/")) "/" else ""

        if (schemeString.isEmpty() && hostString.isEmpty() && portString.isEmpty() && relativePath.startsWith("/")) {
             // This is a path-only URL like /foo/bar
             return relativePath
        }
        return "$schemeString$hostString$portString$pathPrefix$relativePath"
    }
}
