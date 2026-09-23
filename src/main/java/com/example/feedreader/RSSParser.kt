package com.example.feedreader

import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.*

/**
 * RSS Feed Parser - adapted from ReadYou's RSS parsing logic
 */
class RSSParser {

    /**
     * Parses an RSS feed and returns a list of articles
     */
    fun parseRSS(url: String): List<RSSArticle> {
        val articles = mutableListOf<RSSArticle>()
        
        try {
            val inputStream = BufferedReader(InputStreamReader(URL(url)))
            val xml = inputStream.readText()
            val parsed = parseXml(xml)
            articles.addAll(parsed)
        } catch (e: Exception) {
            println("Failed to parse RSS feed: $e")
        }
        
        return articles
    }

    /**
     * Parses XML and extracts article elements
     */
    private fun parseXml(xml: String): List<RSSArticle> {
        val articles = mutableListOf<RSSArticle>()
        
        // Simple XML parsing - in production, use a proper XML parser like JAXB or DOM
        // This is a simplified version for demonstration
        val startTag = "<item>"
        val endTag = "</item>"
        
        val stack = Stack<String>()
        var currentItem = null
        var currentArticle = null
        
        val lines = xml.split("
")
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            
            // Find opening <item> tag
            if (line.startsWith("<item>")) {
                stack.push(line)
                currentItem = line
                i += 1
                continue
            }
            
            // Find closing </item> tag
            if (line.startsWith("</item>")) {
                stack.pop()
                i += 1
                continue
            }
            
            // Extract article title (first <title> element)
            if (currentItem?.contains("<title>") == true) {
                val titleMatch = currentItem.indexOf("<title>")
                if (titleMatch >= 0) {
                    val titleEnd = currentItem.indexOf("</title>", titleMatch)
                    val title = currentItem.substring(titleMatch, titleEnd)
                    currentArticle = RSSArticle(title = title.trim(), url = "")
                }
            }
            
            // Extract excerpt (first <description> element)
            if (currentItem?.contains("<description>") == true) {
                val descMatch = currentItem.indexOf("<description>")
                if (descMatch >= 0) {
                    val descEnd = currentItem.indexOf("</description>", descMatch)
                    val excerpt = currentItem.substring(descMatch, descEnd)
                    currentArticle?.excerpt = excerpt.trim()
                }
            }
            
            // Extract pubDate
            if (currentItem?.contains("<pubDate>") == true) {
                val dateMatch = currentItem.indexOf("<pubDate>")
                if (dateMatch >= 0) {
                    val dateEnd = currentItem.indexOf("</pubDate>", dateMatch)
                    currentArticle?.pubDate = dateEnd ?: ""
                }
            }
            
            // Extract author
            if (currentItem?.contains("<author>") == true) {
                val authorMatch = currentItem.indexOf("<author>")
                if (authorMatch >= 0) {
                    val authorEnd = currentItem.indexOf("</author>", authorMatch)
                    currentArticle?.author = authorEnd ?: ""
                }
            }
            
            i += 1
        }
        
        articles.addAll(currentArticles)
        return articles
    }

    /**
     * Helper to extract text between tags
     */
    private fun extractTextBetween(startTag: String, endTag: String): String? {
        val lines = StringBuilder().apply { append(line) }
        var inTag = false
        var startPos = -1
        var endPos = -1
        
        for (line in lines.toString().split("\n")) {
            if (line.startsWith(startTag)) {
                inTag = true
                startPos = line.indexOf(startTag)
            } else if (inTag && line.contains(endTag)) {
                endPos = line.indexOf(endTag)
                return line.substring(startPos, endPos)
            }
        }
        return null
    }
}

// Data class representing an RSS Article
data class RSSArticle(
    val title: String,
    val excerpt: String,
    val pubDate: String,
    val author: String,
    val url: String = ""
) {
}
