package inodes.service;

import inodes.Configuration;
import inodes.models.Document;
import inodes.service.api.DataService;
import inodes.service.api.UserGroupService;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.apache.solr.client.solrj.request.schema.SchemaRequest;
import org.apache.solr.client.solrj.response.FacetField;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.client.solrj.response.schema.SchemaResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.SolrInputDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class SolrDataService extends DataService {

    Logger LOG = LoggerFactory.getLogger(SolrDataService.class);

    @Autowired
    Configuration conf;

    HttpSolrClient solr;

    @PostConstruct
    void init() {
        String urlString = conf.getProperty("searchservice.solr.url");
        solr = new HttpSolrClient.Builder(urlString).build();
        registerSchema();
    }

    void registerSchema() {
        Class<Document> klass = Document.class;
        Arrays.stream(klass.getDeclaredFields())
                .filter(f -> !Modifier.isTransient(f.getModifiers()))
                .map(f -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", f.getName());
                    m.put("type", mapType(f.getType()));
                    m.put("multiValued", isMultiValued(f.getType()));
                    m.put("stored", true);
                    m.put("indexed", true);
                    m.put("required", true);
                    return m;
                })
                .map(m -> new SchemaRequest.AddField(m))
                .forEach(a -> {
                    try {
                        a.process(solr);
                    } catch (Exception e) {
                        LOG.error("Solr add field failed.", e);
                    }
                });
    }

    private boolean isMultiValued(Class<?> type) {
        return type.isArray() || type.isAssignableFrom(List.class);
    }

    Map<String, String> clasToTypeMap = new HashMap<String, String>() {{
        put("String", "text_general");
        put("long", "plongs");
        put("List", "text_general");
        put("int", "plongs");
        put("boolean", "booleans");
    }};
    private String mapType(Class<?> klass) {
        if(clasToTypeMap.containsKey(klass.getSimpleName()))
            return clasToTypeMap.get(klass.getSimpleName());
        return "text_general";
    }

    private String getSearchQuery(String userId, SearchQuery sq) throws Exception {
        String visibility = String.format("visibility:(%s) AND ",
                sq.getVisibility().stream().map(x -> String.format("\"%s\"", x)).collect(Collectors.joining(" OR ")));

        if (sq.getId() != null && !sq.getId().isEmpty()) {
            return String.format("%s id:(%s)", visibility, sq.getId());
        }

        String[] chunks = sq.getQ().split("\\s+");
        StringBuilder q = new StringBuilder();
        q.append(visibility);
        for (String chunk : chunks) {
            if (chunk.charAt(0) == '#') {
                q.append("tags:(")
                        .append(chunk.substring(1))
                        .append("*) AND ");
            } else if (chunk.charAt(0) == '~') {
                q.append("owner:(")
                        .append(chunk.substring(1))
                        .append("*) AND ");
            } else if (chunk.charAt(0) == '%') {
                q.append("type:(*")
                        .append(chunk.substring(1))
                        .append("*) AND ");
            } else if (chunk.charAt(0) == '@') {
                q.append("id:(")
                        .append(chunk.substring(1))
                        .append(") AND ");
            } else {
                q.append("content:(*")
                        .append(chunk)
                        .append("*) AND ");
            }
        }
        return q.substring(0, q.length() - 5);
    }

    public SearchResponse _search(String userId, SearchQuery sq) throws Exception {
        SolrQuery query = new SolrQuery();
        query.set("q", getSearchQuery(userId, sq));
        query.setStart((int) sq.getOffset());
        query.setRows(sq.getPageSize());

        if (sq.getFq() != null && !sq.getFq().isEmpty()) {
            query.setFacet(true);
            query.setFacetLimit(sq.getFqLimit() < 1 ? 10 : sq.getFqLimit());
            query.setFacetSort("count");
            sq.getFq().forEach(ff -> query.addFacetField(ff));
        }

        LOG.info("Search query: {}", query);
        QueryResponse response = solr.query(query);

        // docs
        List<Document> docs = response.getBeans(Document.class);

        // facets
        List<FacetField> ffs = response.getFacetFields();
        Map<String, Map<String, Long>> facetResults = new HashMap<>();
        if(ffs != null) {
            ffs.forEach(ff -> {
                Map<String, Long> m = new HashMap<>();
                facetResults.put(ff.getName(), m);
                ff.getValues().forEach(fc -> {
                    m.put(fc.getName(), fc.getCount());
                });
            });
        }

        SearchResponse resp = new SearchResponse();
        resp.setResults(docs);
        resp.setTotalResults(response.getResults().getNumFound());
        resp.setFacetResults(facetResults);

        return resp;
    }

    public void _deleteObj(String id) throws Exception {
        solr.deleteById(id);
        solr.commit();
    }

    public void _putData(Document doc) throws IOException {
        try {
            solr.addBean(doc);
            solr.commit();
        } catch (SolrServerException e) {
            e.printStackTrace();
        }
    }

}
