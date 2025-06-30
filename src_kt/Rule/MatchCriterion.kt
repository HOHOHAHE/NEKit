
package com.example.nekit.Rule

abstract class MatchCriterion {
    /**
     * Abstract method to check if the given domain string matches this criterion.
     * @param domain The domain string to test.
     * @return True if the domain matches, false otherwise.
     */
    abstract fun matches(domain: String): Boolean

    /**
     * Matches the domain against a regular expression.
     * @property regex The Kotlin Regex object.
     */
    data class RegexCriterion(val regex: Regex) : MatchCriterion() {
        override fun matches(domain: String): Boolean = regex.containsMatchIn(domain)
    }

    /**
     * Matches if the domain starts with the given prefix.
     * @property prefix The prefix string. Case sensitivity depends on usage.
     *                  For domain matching, typically case-insensitive. Consider toLowerCasing both if needed.
     */
    data class PrefixCriterion(val prefix: String, val ignoreCase: Boolean = true) : MatchCriterion() {
        override fun matches(domain: String): Boolean = domain.startsWith(prefix, ignoreCase)
    }

    /**
     * Matches if the domain ends with the given suffix.
     * @property suffix The suffix string. Case sensitivity depends on usage.
     */
    data class SuffixCriterion(val suffix: String, val ignoreCase: Boolean = true) : MatchCriterion() {
        override fun matches(domain: String): Boolean = domain.endsWith(suffix, ignoreCase)
    }

    /**
     * Matches if the domain contains the given keyword.
     * @property keyword The keyword string. Case sensitivity depends on usage.
     */
    data class KeywordCriterion(val keyword: String, val ignoreCase: Boolean = true) : MatchCriterion() {
        override fun matches(domain: String): Boolean = domain.contains(keyword, ignoreCase)
    }

    /**
     * Matches if the domain is an exact match to the given string.
     * @property matchString The string to match completely. Case sensitivity depends on usage.
     */
    data class CompleteCriterion(val matchString: String, val ignoreCase: Boolean = true) : MatchCriterion() {
        override fun matches(domain: String): Boolean = domain.equals(matchString, ignoreCase)
    }
}