/*
 * Copyright (c) 2026 GnuCash Pocket contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gnucash.android.sync.webdav

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory

/** The outcome of parsing one `207 Multi-Status` body. */
sealed interface MultiStatusResult {

    /**
     * The document was well formed.
     *
     * @param entries          the `2xx` responses, in document order.
     * @param failedResponses  hrefs (or a placeholder) of `<response>` blocks that carried a
     *                         non-2xx status. **Non-empty here means the listing is not
     *                         trustworthy** — see [WebDavClient].
     */
    data class Parsed(
        val entries: List<RemoteEntry>,
        val failedResponses: List<String>
    ) : MultiStatusResult

    /** The body was not a usable `multistatus` document. [reason] is for the log. */
    data class Malformed(val reason: String) : MultiStatusResult
}

/**
 * Minimal, defensive reader/writer for the two WebDAV XML fragments the client needs:
 * the `PROPFIND` request body and the `207` response.
 *
 * Deliberately hand-rolled over `javax.xml` rather than pulling in a DAV library: the
 * whole point of the plan's ② is to stop depending on the ownCloud SDK, and the surface
 * actually used here is four properties deep.
 *
 * Tolerances that matter for real servers (Nextcloud, 坚果云, Apache mod_dav, SabreDAV):
 *  - the namespace prefix is arbitrary (`D:`, `d:`, `ns0:`) and may be absent entirely
 *    (default namespace), so every lookup matches on the **local name**;
 *  - `<resourcetype/>` is often self-closing;
 *  - a `<propstat>` may omit its `<status>`.
 *
 * One thing it is **not** tolerant of, on purpose: a `DOCTYPE`. A DAV response has no
 * legitimate use for one, and accepting it would hand an untrusted server an XXE
 * primitive (see the file-level test for the payload).
 */
object WebDavXml {

    const val DAV_NAMESPACE = "DAV:"

    /**
     * The properties the client asks for. Sending an explicit list (rather than
     * `allprop`) keeps the response small and, more importantly, keeps the
     * response shape predictable enough to parse defensively.
     */
    fun propfindBody(): String =
        """<?xml version="1.0" encoding="utf-8"?>
           |<d:propfind xmlns:d="DAV:">
           |  <d:prop>
           |    <d:resourcetype/>
           |    <d:getetag/>
           |    <d:getcontentlength/>
           |    <d:getlastmodified/>
           |  </d:prop>
           |</d:propfind>
        """.trimMargin()

    /** Parses a `207` body. Never throws. */
    fun parseMultiStatus(xml: String): MultiStatusResult {
        if (xml.isBlank()) return MultiStatusResult.Malformed("empty body")
        if (xml.contains("<!DOCTYPE", ignoreCase = true)) {
            return MultiStatusResult.Malformed("DOCTYPE declarations are not allowed")
        }

        val document: Document = try {
            newDocumentBuilder().parse(InputSource(StringReader(xml)))
        } catch (e: Exception) {
            return MultiStatusResult.Malformed(e.message ?: e.javaClass.simpleName)
        }

        val root = document.documentElement ?: return MultiStatusResult.Malformed("no document element")
        if (localName(root) != "multistatus") {
            return MultiStatusResult.Malformed("root element is <${root.tagName}>, expected <multistatus>")
        }

        val entries = mutableListOf<RemoteEntry>()
        val failed = mutableListOf<String>()

        for (response in directChildren(root, "response")) {
            val href = firstDescendant(response, "href")?.textContent?.trim()
            if (href.isNullOrEmpty()) {
                failed += "<response> without an <href>"
                continue
            }

            val prop = okProp(response)
            if (prop == null) {
                failed += "$href -> ${statusTexts(response).joinToString(", ")
                    .ifEmpty { "no <status> in any <propstat>" }}"
                continue
            }

            entries += RemoteEntry(
                path = WebDavPath.normalize(href),
                href = href,
                isCollection = prop?.let { firstDescendant(it, "collection") } != null,
                etag = prop?.let { firstDescendant(it, "getetag") }?.textContent?.let(::normalizeEtag),
                contentLength = prop?.let { firstDescendant(it, "getcontentlength") }
                    ?.textContent?.trim()?.toLongOrNull(),
                lastModified = prop?.let { firstDescendant(it, "getlastmodified") }
                    ?.textContent?.trim()?.takeIf { it.isNotEmpty() }
            )
        }

        return MultiStatusResult.Parsed(entries, failed)
    }

    /**
     * Normalises an entity tag for equality comparison.
     *
     * Servers disagree about the quoting (`"abc"` vs `abc`) and about whether to send a
     * weak tag (`W/"abc"`). Since the comparison is only ever "did *this* server's
     * representation change since the last listing of *this* server", collapsing the
     * weaker distinctions is safe and removes a class of spurious "changed" verdicts.
     */
    private fun normalizeEtag(raw: String): String =
        raw.trim().removePrefix("W/").trim().removeSurrounding("\"")

    /**
     * All status strings that apply to a `<response>`: a resource-level `<status>` plus each
     * `<propstat>`'s own `<status>`. Used only to explain a failure in the log.
     */
    private fun statusTexts(response: Element): List<String> {
        val out = mutableListOf<String>()
        for (status in directChildren(response, "status")) out += normalizeStatus(status.textContent)
        for (propstat in directChildren(response, "propstat")) {
            for (status in directChildren(propstat, "status")) out += normalizeStatus(status.textContent)
        }
        return out
    }

    private fun normalizeStatus(raw: String): String =
        raw.trim().removePrefix("HTTP/1.1 ").removePrefix("HTTP/1.0 ")

    /**
     * The `<prop>` of the first `<propstat>` that carries a **2xx** status, or `null`
     * when the response has none — which makes it a failed response. See the class KDoc
     * for why a status-less `<propstat>` is refused rather than assumed good.
     */
    private fun okProp(response: Element): Element? {
        for (propstat in directChildren(response, "propstat")) {
            val succeeded = directChildren(propstat, "status")
                .any { normalizeStatus(it.textContent).startsWith("2") }
            if (!succeeded) continue
            directChildren(propstat, "prop").firstOrNull()?.let { return it }
        }
        return null
    }

    private fun localName(element: Element): String =
        element.localName ?: element.tagName.substringAfterLast(':')

    private fun directChildren(parent: Element, localName: String): List<Element> {
        val out = mutableListOf<Element>()
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child is Element && localName(child) == localName) out += child
        }
        return out
    }

    /** First element at any depth whose local name matches — namespace-prefix agnostic. */
    private fun firstDescendant(parent: Element, localName: String): Element? {
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child !is Element) continue
            if (localName(child) == localName) return child
            firstDescendant(child, localName)?.let { return it }
        }
        return null
    }

    private fun newDocumentBuilder(): DocumentBuilder {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        factory.isExpandEntityReferences = false
        factory.setFeatureQuietly("http://apache.org/xml/features/disallow-doctype-decl", true)
        factory.setFeatureQuietly("http://xml.org/sax/features/external-general-entities", false)
        factory.setFeatureQuietly("http://xml.org/sax/features/external-parameter-entities", false)
        return factory.newDocumentBuilder()
    }

    /**
     * Enables a parser feature, ignoring platforms that do not know it. The DOCTYPE
     * pre-check above is what actually enforces the XXE rule; these are defence in depth.
     */
    private fun DocumentBuilderFactory.setFeatureQuietly(name: String, value: Boolean) {
        try {
            setFeature(name, value)
        } catch (_: Exception) {
            // Feature unsupported on this platform — the explicit DOCTYPE rejection covers us.
        }
    }
}
