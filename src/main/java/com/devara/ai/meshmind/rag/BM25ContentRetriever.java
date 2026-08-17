package com.devara.ai.meshmind.rag;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Sparse-retrieval companion to the dense vector store.
 * Wraps an in-memory Lucene index with default BM25 similarity; emits DEBUG logs on each retrieve call
 * showing the query, per-hit scores, and text previews so hybrid fusion can be inspected end-to-end.
 */
@Slf4j
public class BM25ContentRetriever implements ContentRetriever {

    private static final String TEXT_FIELD = "text";
    private static final int PREVIEW_CHARS = 160;

    private final int maxResults;
    private final Analyzer analyzer = new StandardAnalyzer();
    private final Directory directory = new ByteBuffersDirectory();
    private volatile IndexReader reader;

    public BM25ContentRetriever(int maxResults) {
        this.maxResults = maxResults;
    }

    /** (Re)builds the in-memory Lucene index from the given text segments. Safe to call at startup. */
    public synchronized void index(List<TextSegment> segments) {
        try {
            IndexWriterConfig config = new IndexWriterConfig(analyzer);
            config.setSimilarity(new BM25Similarity());
            config.setOpenMode(IndexWriterConfig.OpenMode.CREATE);
            try (IndexWriter writer = new IndexWriter(directory, config)) {
                for (TextSegment segment : segments) {
                    Document doc = new Document();
                    doc.add(new TextField(TEXT_FIELD, segment.text(), Field.Store.YES));
                    for (var entry : segment.metadata().toMap().entrySet()) {
                        doc.add(new StoredField("meta_" + entry.getKey(), String.valueOf(entry.getValue())));
                    }
                    writer.addDocument(doc);
                }
                writer.commit();
            }
            if (reader != null) {
                reader.close();
            }
            reader = DirectoryReader.open(directory);
            log.info("Indexed {} segments into BM25", segments.size());
        } catch (IOException e) {
            throw new RuntimeException("Failed to build BM25 index", e);
        }
    }

    @Override
    public List<Content> retrieve(Query query) {
        String text = query.text();
        if (reader == null) {
            log.warn("[bm25] retrieve called before index() was populated; returning empty");
            return List.of();
        }
        try {
            IndexSearcher searcher = new IndexSearcher(reader);
            searcher.setSimilarity(new BM25Similarity());

            QueryParser parser = new QueryParser(TEXT_FIELD, analyzer);
            String escaped = QueryParser.escape(text);
            org.apache.lucene.search.Query luceneQuery = buildRelaxedQuery(parser, escaped);

            TopDocs hits = searcher.search(luceneQuery, maxResults);

            List<Content> results = new ArrayList<>();
            log.debug("[bm25] query=\"{}\" -> {} hits", text, hits.scoreDocs.length);
            int rank = 0;
            for (ScoreDoc sd : hits.scoreDocs) {
                rank++;
                Document doc = searcher.storedFields().document(sd.doc);
                String segText = doc.get(TEXT_FIELD);
                Metadata metadata = readMetadata(doc);
                TextSegment segment = TextSegment.from(segText, metadata);
                results.add(Content.from(segment));
                log.debug("[bm25]   #{} score={} preview=\"{}\"", rank, String.format("%.3f", sd.score), preview(segText));
            }
            return results;
        } catch (Exception e) {
            log.warn("[bm25] search failed for query=\"{}\": {}", text, e.getMessage());
            return List.of();
        }
    }

    private org.apache.lucene.search.Query buildRelaxedQuery(QueryParser parser, String escaped) throws Exception {
        // OR-of-terms so partial keyword matches still score, unlike strict AND parsing.
        BooleanQuery.Builder builder = new BooleanQuery.Builder();
        for (String token : escaped.split("\\s+")) {
            if (token.isBlank()) continue;
            builder.add(new BooleanClause(parser.parse(token), BooleanClause.Occur.SHOULD));
        }
        BooleanQuery bq = builder.build();
        return bq.clauses().isEmpty() ? parser.parse(escaped) : bq;
    }

    private Metadata readMetadata(Document doc) {
        Metadata metadata = new Metadata();
        for (var field : doc.getFields()) {
            String name = field.name();
            if (name.startsWith("meta_")) {
                metadata.put(name.substring("meta_".length()), field.stringValue());
            }
        }
        return metadata;
    }

    private static String preview(String s) {
        if (s == null) return "";
        String flat = s.replaceAll("\\s+", " ").trim();
        return flat.length() <= PREVIEW_CHARS ? flat : flat.substring(0, PREVIEW_CHARS) + "...";
    }
}
