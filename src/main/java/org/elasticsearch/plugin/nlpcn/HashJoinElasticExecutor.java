package org.elasticsearch.plugin.nlpcn;

import com.alibaba.druid.sql.ast.statement.SQLJoinTableSource;
import org.elasticsearch.action.search.SearchRequestBuilder;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.SearchType;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.text.StringText;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.index.query.BoolFilterBuilder;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.FilterBuilders;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.internal.InternalSearchHit;
import org.nlpcn.es4sql.domain.Field;
import org.nlpcn.es4sql.domain.Select;
import org.nlpcn.es4sql.domain.Where;
import org.nlpcn.es4sql.exception.SqlParseException;
import org.nlpcn.es4sql.query.join.HashJoinElasticRequestBuilder;
import org.nlpcn.es4sql.query.join.TableInJoinRequestBuilder;
import org.nlpcn.es4sql.query.maker.FilterMaker;
import org.nlpcn.es4sql.query.maker.QueryMaker;

import java.io.IOException;
import java.util.*;

/**
 * Created by Eliran on 22/8/2015.
 */
public class HashJoinElasticExecutor extends ElasticJoinExecutor {
    private HashJoinElasticRequestBuilder requestBuilder;


    private Client client;
    private boolean useQueryTermsFilterOptimization = false;
    private final int MAX_RESULTS_FOR_FIRST_TABLE = 100000;
    HashJoinComparisonStructure hashJoinComparisonStructure;
    private Set<String> alreadyMatched;

    public HashJoinElasticExecutor(Client client, HashJoinElasticRequestBuilder requestBuilder) {
        super(requestBuilder);
        this.client = client;
        this.requestBuilder = requestBuilder;
        this.useQueryTermsFilterOptimization = requestBuilder.isUseTermFiltersOptimization();
        this.hashJoinComparisonStructure = new HashJoinComparisonStructure(requestBuilder.getT1ToT2FieldsComparison());
        this.alreadyMatched = new HashSet<>();
    }

    public List<InternalSearchHit> innerRun() throws IOException, SqlParseException {

        Map<String, Map<String, List<Object>>> optimizationTermsFilterStructure =
                initOptimizationStructure();


        updateFirstTableLimitIfNeeded();
        TableInJoinRequestBuilder firstTableRequest = requestBuilder.getFirstTable();
        createKeyToResultsAndFillOptimizationStructure(optimizationTermsFilterStructure, firstTableRequest);

        TableInJoinRequestBuilder secondTableRequest = requestBuilder.getSecondTable();
        if (needToOptimize(optimizationTermsFilterStructure)) {
            updateRequestWithTermsFilter(optimizationTermsFilterStructure, secondTableRequest);
        }

        List<InternalSearchHit> combinedResult = createCombinedResults(secondTableRequest);

        int currentNumOfResults = combinedResult.size();
        int totalLimit = requestBuilder.getTotalLimit();
        if (requestBuilder.getJoinType() == SQLJoinTableSource.JoinType.LEFT_OUTER_JOIN && currentNumOfResults < totalLimit) {
            String t1Alias = requestBuilder.getFirstTable().getAlias();
            String t2Alias = requestBuilder.getSecondTable().getAlias();
            //todo: for each till Limit
            addUnmatchedResults(combinedResult, this.hashJoinComparisonStructure.getAllSearchHits(),
                    requestBuilder.getSecondTable().getReturnedFields(),
                    currentNumOfResults, totalLimit,
                    t1Alias,
                    t2Alias);
        }
        return combinedResult;
    }

    private Map<String, Map<String, List<Object>>> initOptimizationStructure() {
        Map<String,Map<String, List<Object>>> optimizationTermsFilterStructure = new HashMap<>();
        for(String comparisonId: this.hashJoinComparisonStructure.getComparisons().keySet()){
            optimizationTermsFilterStructure.put(comparisonId,new HashMap<String, List<Object>>());
        }
        return optimizationTermsFilterStructure;
    }

    private void updateFirstTableLimitIfNeeded() {
        if (requestBuilder.getJoinType() == SQLJoinTableSource.JoinType.LEFT_OUTER_JOIN) {
            Integer firstTableHintLimit = requestBuilder.getFirstTable().getHintLimit();
            int totalLimit = requestBuilder.getTotalLimit();
            if (firstTableHintLimit == null || firstTableHintLimit > totalLimit) {
                requestBuilder.getFirstTable().setHintLimit(totalLimit);
            }
        }
    }

    private List<InternalSearchHit> createCombinedResults( TableInJoinRequestBuilder secondTableRequest) {
        List<InternalSearchHit> combinedResult = new ArrayList<>();
        int resultIds = 0;
        int totalLimit = this.requestBuilder.getTotalLimit();
        Integer hintLimit = secondTableRequest.getHintLimit();
        SearchResponse searchResponse;
        boolean finishedScrolling;
        if (hintLimit != null && hintLimit < MAX_RESULTS_ON_ONE_FETCH) {
            searchResponse = secondTableRequest.getRequestBuilder().setSize(hintLimit).get();
            finishedScrolling = true;
        } else {
            searchResponse = secondTableRequest.getRequestBuilder()
                    .setSearchType(SearchType.SCAN)
                    .setScroll(new TimeValue(60000))
                    .setSize(MAX_RESULTS_ON_ONE_FETCH).get();
            searchResponse = client.prepareSearchScroll(searchResponse.getScrollId()).setScroll(new TimeValue(600000)).get();
            finishedScrolling = false;
        }
        updateMetaSearchResults(searchResponse);

        boolean limitReached = false;
        int fetchedSoFarFromSecondTable = 0;
        while (!limitReached) {
            SearchHit[] secondTableHits = searchResponse.getHits().getHits();
            fetchedSoFarFromSecondTable += secondTableHits.length;
            for (SearchHit secondTableHit : secondTableHits) {
                if (limitReached) break;
                //todo: need to run on comparisons. for each comparison check if exists and add.
                HashMap<String, List<Map.Entry<Field, Field>>> comparisons = this.hashJoinComparisonStructure.getComparisons();

                for (Map.Entry<String, List<Map.Entry<Field, Field>>> comparison : comparisons.entrySet()) {
                    String comparisonID = comparison.getKey();
                    List<Map.Entry<Field, Field>> t1ToT2FieldsComparison = comparison.getValue();
                    String key = getComparisonKey(t1ToT2FieldsComparison, secondTableHit, false, null);

                    SearchHitsResult searchHitsResult = this.hashJoinComparisonStructure.searchForMatchingSearchHits(comparisonID, key);

                    if (searchHitsResult != null && searchHitsResult.getSearchHits().size() > 0) {
                        searchHitsResult.setMatchedWithOtherTable(true);
                        List<InternalSearchHit> searchHits = searchHitsResult.getSearchHits();
                        for (InternalSearchHit matchingHit : searchHits) {
                            String combinedId = matchingHit.id() + "|" + secondTableHit.getId();
                            //in order to prevent same matching when using OR on hashJoins.
                            if(this.alreadyMatched.contains(combinedId)){
                                continue;
                            }
                            else {
                                this.alreadyMatched.add(combinedId);
                            }

                            Map<String,Object> copiedSource = new HashMap<String,Object>();
                            copyMaps(copiedSource,secondTableHit.sourceAsMap());
                            onlyReturnedFields(copiedSource, secondTableRequest.getReturnedFields());



                            InternalSearchHit searchHit = new InternalSearchHit(resultIds, combinedId, new StringText(matchingHit.getType() + "|" + secondTableHit.getType()), matchingHit.getFields());
                            searchHit.sourceRef(matchingHit.getSourceRef());
                            searchHit.sourceAsMap().clear();
                            searchHit.sourceAsMap().putAll(matchingHit.sourceAsMap());
                            String t1Alias = requestBuilder.getFirstTable().getAlias();
                            String t2Alias = requestBuilder.getSecondTable().getAlias();
                            mergeSourceAndAddAliases(copiedSource, searchHit, t1Alias, t2Alias);

                            combinedResult.add(searchHit);
                            resultIds++;
                            if (resultIds >= totalLimit) {
                                limitReached = true;
                                break;
                            }
                        }
                    }
                }
            }
            if (!finishedScrolling) {
                if (secondTableHits.length > 0 && (hintLimit == null || fetchedSoFarFromSecondTable >= hintLimit)) {
                    searchResponse = client.prepareSearchScroll(searchResponse.getScrollId()).setScroll(new TimeValue(600000)).execute().actionGet();
                } else break;
            } else {
                break;
            }
        }
        return combinedResult;
    }

    private void copyMaps(Map<String, Object> into, Map<String, Object> from) {
        for(Map.Entry<String,Object> keyAndValue : from.entrySet())
            into.put(keyAndValue.getKey(),keyAndValue.getValue());
    }

    private void createKeyToResultsAndFillOptimizationStructure(Map<String,Map<String, List<Object>>> optimizationTermsFilterStructure, TableInJoinRequestBuilder firstTableRequest) {
        List<SearchHit> firstTableHits = fetchAllHits(firstTableRequest.getRequestBuilder(), firstTableRequest.getHintLimit());

        int resultIds = 1;
        for (SearchHit hit : firstTableHits) {
            HashMap<String, List<Map.Entry<Field, Field>>> comparisons = this.hashJoinComparisonStructure.getComparisons();
            for (Map.Entry<String, List<Map.Entry<Field, Field>>> comparison : comparisons.entrySet()) {
                String comparisonID = comparison.getKey();
                List<Map.Entry<Field, Field>> t1ToT2FieldsComparison = comparison.getValue();

                String key = getComparisonKey(t1ToT2FieldsComparison, hit, true, optimizationTermsFilterStructure.get(comparisonID));

                //int docid , id
                InternalSearchHit searchHit = new InternalSearchHit(resultIds, hit.id(), new StringText(hit.getType()), hit.getFields());
                searchHit.sourceRef(hit.getSourceRef());

                onlyReturnedFields(searchHit.sourceAsMap(), firstTableRequest.getReturnedFields());
                resultIds++;
                this.hashJoinComparisonStructure.insertIntoComparisonHash(comparisonID, key, searchHit);
            }
        }
    }

    private List<SearchHit> fetchAllHits(SearchRequestBuilder requestBuilder, Integer hintLimit) {
        if (hintLimit != null && hintLimit < MAX_RESULTS_ON_ONE_FETCH) {
            requestBuilder.setSize(hintLimit);
            SearchResponse searchResponse = requestBuilder.get();
            updateMetaSearchResults(searchResponse);
            return Arrays.asList(searchResponse.getHits().getHits());
        }
        return scrollTillLimit(requestBuilder, hintLimit);
    }

    private List<SearchHit> scrollTillLimit(SearchRequestBuilder requestBuilder, Integer hintLimit) {
        SearchResponse scrollResp = requestBuilder.setSearchType(SearchType.SCAN)
                .setScroll(new TimeValue(60000))
                .setSize(MAX_RESULTS_ON_ONE_FETCH).get();

        scrollResp = client.prepareSearchScroll(scrollResp.getScrollId()).setScroll(new TimeValue(600000)).get();
        updateMetaSearchResults(scrollResp);
        List<SearchHit> hitsWithScan = new ArrayList<>();
        int curentNumOfResults = 0;
        SearchHit[] hits = scrollResp.getHits().hits();

        if (hintLimit == null) hintLimit = MAX_RESULTS_FOR_FIRST_TABLE;

        while (hits.length != 0 && curentNumOfResults < hintLimit) {
            curentNumOfResults += hits.length;
            Collections.addAll(hitsWithScan, hits);
            if (curentNumOfResults >= MAX_RESULTS_FOR_FIRST_TABLE) {
                //todo: log or exception?
                System.out.println("too many results for first table, stoping at:" + curentNumOfResults);
                break;
            }
            scrollResp = client.prepareSearchScroll(scrollResp.getScrollId()).setScroll(new TimeValue(600000)).execute().actionGet();
            hits = scrollResp.getHits().getHits();
        }
        return hitsWithScan;
    }

    private boolean needToOptimize(Map<String,Map<String, List<Object>>> optimizationTermsFilterStructure) {
        if(! useQueryTermsFilterOptimization && optimizationTermsFilterStructure != null && optimizationTermsFilterStructure.size() > 0)
            return false;
        boolean allEmpty = true;
        for(Map<String,List<Object>> optimization : optimizationTermsFilterStructure.values()){
            if(optimization.size() > 0){
                allEmpty = false;
                break;
            }
        }
        return !allEmpty;
    }

    private void updateRequestWithTermsFilter(Map<String,Map<String, List<Object>>> optimizationTermsFilterStructure, TableInJoinRequestBuilder secondTableRequest) throws SqlParseException {
        Select select = secondTableRequest.getOriginalSelect();
        BoolFilterBuilder orFilter = FilterBuilders.boolFilter();
        BoolQueryBuilder orQuery = QueryBuilders.boolQuery();
        for(Map<String,List<Object>> optimization : optimizationTermsFilterStructure.values()) {
            BoolFilterBuilder andFilter = FilterBuilders.boolFilter();
            BoolQueryBuilder andQuery = QueryBuilders.boolQuery();
            for (Map.Entry<String, List<Object>> keyToValues : optimization.entrySet()) {
                String fieldName = keyToValues.getKey();
                List<Object> values = keyToValues.getValue();
                if (select.isQuery) andQuery.must(QueryBuilders.termsQuery(fieldName, values));
                else andFilter.must(FilterBuilders.termsFilter(fieldName, values));
            }
            orFilter.should(andFilter);
            orQuery.should(andQuery);
        }

        Where where = select.getWhere();
        if (select.isQuery) {
            BoolQueryBuilder boolQuery;
            if (where != null) {
                boolQuery = QueryMaker.explan(where);
                boolQuery.must(orQuery);
            } else boolQuery = orQuery;
            secondTableRequest.getRequestBuilder().setQuery(boolQuery);
        } else {
            BoolFilterBuilder boolFilter;
            if (where != null) {
                boolFilter = FilterMaker.explan(where);
                boolFilter.must(orFilter);
            } else boolFilter = orFilter;
            secondTableRequest.getRequestBuilder().setQuery(QueryBuilders.filteredQuery(null, boolFilter));
        }
    }

    private String getComparisonKey(List<Map.Entry<Field, Field>> t1ToT2FieldsComparison, SearchHit hit, boolean firstTable, Map<String, List<Object>> optimizationTermsFilterStructure) {
        String key = "";
        Map<String, Object> sourceAsMap = hit.sourceAsMap();
        for (Map.Entry<Field, Field> t1ToT2 : t1ToT2FieldsComparison) {
            //todo: change to our function find if key contains '.'
            String name;
            if (firstTable) name = t1ToT2.getKey().getName();
            else name = t1ToT2.getValue().getName();

            Object data = deepSearchInMap(sourceAsMap, name);
            if (firstTable && useQueryTermsFilterOptimization) {
                updateOptimizationData(optimizationTermsFilterStructure, data, t1ToT2.getValue().getName());
            }
            if (data == null)
                key += "|null|";
            else
                key += "|" + data.toString() + "|";
        }
        return key;
    }

    private void updateOptimizationData(Map<String, List<Object>> optimizationTermsFilterStructure, Object data, String queryOptimizationKey) {
        List<Object> values = optimizationTermsFilterStructure.get(queryOptimizationKey);
        if (values == null) {
            values = new ArrayList<>();
            optimizationTermsFilterStructure.put(queryOptimizationKey, values);
        }
        if (data instanceof String) {
            //todo: analyzed or not analyzed check..
            data = ((String) data).toLowerCase();
        }
        if(data!=null)
            values.add(data);
    }
}
