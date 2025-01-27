package io.dataease.provider.datasource;


import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import io.dataease.commons.utils.Md5Utils;
import io.dataease.dto.dataset.DatasetTableFieldDTO;
import io.dataease.plugins.common.dto.datasource.TableDesc;
import io.dataease.plugins.common.dto.datasource.TableField;
import io.dataease.plugins.common.request.datasource.DatasourceRequest;
import io.dataease.plugins.datasource.provider.Provider;
import com.jayway.jsonpath.JsonPath;
import io.dataease.commons.utils.HttpClientConfig;
import io.dataease.commons.utils.HttpClientUtil;
import io.dataease.controller.request.datasource.ApiDefinition;
import io.dataease.controller.request.datasource.ApiDefinitionRequest;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import org.springframework.stereotype.Service;


import java.util.*;
import java.util.stream.Collectors;

@Service("apiProvider")
public class ApiProvider extends Provider {


    @Override
    public List<String[]> getData(DatasourceRequest datasourceRequest) throws Exception {
        ApiDefinition apiDefinition = checkApiDefinition(datasourceRequest);
        String response = execHttpRequest(apiDefinition);
        return fetchResult(response, apiDefinition);
    }

    @Override
    public List<TableDesc> getTables(DatasourceRequest datasourceRequest) throws Exception {
        List<TableDesc> tableDescs = new ArrayList<>();
        List<ApiDefinition> lists = new Gson().fromJson(datasourceRequest.getDatasource().getConfiguration(), new TypeToken<List<ApiDefinition>>() {
        }.getType());
        for (ApiDefinition apiDefinition : lists) {
            TableDesc tableDesc = new TableDesc();
            tableDesc.setName(apiDefinition.getName());
            tableDesc.setRemark(apiDefinition.getDesc());
            tableDescs.add(tableDesc);
        }
        return tableDescs;
    }

    @Override
    public List<TableField> fetchResultField(DatasourceRequest datasourceRequest) throws Exception {
        return null;
    }

    public Map<String, List> fetchResultAndField(DatasourceRequest datasourceRequest) throws Exception {
        Map<String, List> result = new HashMap<>();
        List<String[]> dataList = new ArrayList<>();
        List<TableField> fieldList = new ArrayList<>();
        ApiDefinition apiDefinition = checkApiDefinition(datasourceRequest);
        String response = execHttpRequest(apiDefinition);

        fieldList = getTableFileds(apiDefinition, response);
        result.put("fieldList", fieldList);
        dataList = fetchResult(response, apiDefinition);
        result.put("dataList", dataList);
        return result;
    }


    private List<TableField> getTableFileds(ApiDefinition apiDefinition, String response) throws Exception {
        List<TableField> tableFields = new ArrayList<>();
        for (DatasetTableFieldDTO field : checkApiDefinition(apiDefinition, response).getFields()) {
            TableField tableField = new TableField();
            tableField.setFieldName(field.getOriginName());
            tableField.setRemarks(field.getName());
            tableField.setFieldSize(field.getSize());
            tableField.setFieldType(field.getDeExtractType().toString());
            tableFields.add(tableField);
        }
        return tableFields;
    }

    public List<TableField> getTableFileds(DatasourceRequest datasourceRequest) throws Exception {
        List<ApiDefinition> lists = new Gson().fromJson(datasourceRequest.getDatasource().getConfiguration(), new TypeToken<List<ApiDefinition>>() {
        }.getType());
        List<TableField> tableFields = new ArrayList<>();
        for (ApiDefinition apiDefinition : lists) {
            if (datasourceRequest.getTable().equalsIgnoreCase(apiDefinition.getName())) {
                String response = ApiProvider.execHttpRequest(apiDefinition);
                for (DatasetTableFieldDTO field : checkApiDefinition(apiDefinition, response).getFields()) {
                    TableField tableField = new TableField();
                    tableField.setFieldName(field.getOriginName());
                    tableField.setRemarks(field.getName());
                    tableField.setFieldSize(field.getSize());
                    tableField.setFieldType(field.getDeExtractType().toString());
                    tableFields.add(tableField);
                }
            }
        }
        return tableFields;
    }

    public String checkStatus(DatasourceRequest datasourceRequest) throws Exception {
        Gson gson = new Gson();
        List<ApiDefinition> apiDefinitionList = gson.fromJson(datasourceRequest.getDatasource().getConfiguration(), new TypeToken<List<ApiDefinition>>() {
        }.getType());
        JsonObject apiItemStatuses = new JsonObject();
        for (ApiDefinition apiDefinition : apiDefinitionList) {
            datasourceRequest.setTable(apiDefinition.getName());
            try {
                getData(datasourceRequest);
                apiItemStatuses.addProperty(apiDefinition.getName(), "Success");
            } catch (Exception ignore) {
                apiItemStatuses.addProperty(apiDefinition.getName(), "Error");
            }
        }
        return gson.toJson(apiItemStatuses);
    }

    static public String execHttpRequest(ApiDefinition apiDefinition) throws Exception {
        String response = "";
        HttpClientConfig httpClientConfig = new HttpClientConfig();
        ApiDefinitionRequest apiDefinitionRequest = apiDefinition.getRequest();
        for (Map header : apiDefinitionRequest.getHeaders()) {
            if (header.get("name") != null && StringUtils.isNotEmpty(header.get("name").toString()) && header.get("value") != null && StringUtils.isNotEmpty(header.get("value").toString())) {
                httpClientConfig.addHeader(header.get("name").toString(), header.get("value").toString());
            }
        }

        if (apiDefinitionRequest.getAuthManager() != null
                && StringUtils.isNotBlank(apiDefinitionRequest.getAuthManager().getUsername())
                && StringUtils.isNotBlank(apiDefinitionRequest.getAuthManager().getPassword())
                && apiDefinitionRequest.getAuthManager().getVerification().equals("Basic Auth")) {
            String authValue = "Basic " + Base64.getUrlEncoder().encodeToString((apiDefinitionRequest.getAuthManager().getUsername()
                    + ":" + apiDefinitionRequest.getAuthManager().getPassword()).getBytes());
            httpClientConfig.addHeader("Authorization", authValue);
        }

        switch (apiDefinition.getMethod()) {
            case "GET":
                response = HttpClientUtil.get(apiDefinition.getUrl(), httpClientConfig);
                break;
            case "POST":
                if (apiDefinitionRequest.getBody().get("type") == null) {
                    throw new Exception("请求类型不能为空");
                }
                String type = apiDefinitionRequest.getBody().get("type").toString();
                if (StringUtils.equalsAny(type, "JSON", "XML", "Raw")) {
                    String raw = null;
                    if (apiDefinitionRequest.getBody().get("raw") != null) {
                        raw = apiDefinitionRequest.getBody().get("raw").toString();
                        response = HttpClientUtil.post(apiDefinition.getUrl(), raw, httpClientConfig);
                    }
                }
                if (StringUtils.equalsAny(type, "Form_Data", "WWW_FORM")) {
                    if (apiDefinitionRequest.getBody().get("kvs") != null) {
                        Map<String, String> body = new HashMap<>();
                        JsonArray kvsArr = JsonParser.parseString(apiDefinitionRequest.getBody().get("kvs").toString()).getAsJsonArray();
                        for (int i = 0; i < kvsArr.size(); i++) {
                            JsonObject kv = kvsArr.get(i).getAsJsonObject();
                            if (kv.get("name") != null) {
                                body.put(kv.get("name").getAsString(), kv.get("value").getAsString());
                            }
                        }
                        response = HttpClientUtil.post(apiDefinition.getUrl(), body, httpClientConfig);
                    }
                }
                break;
            default:
                break;
        }
        return response;
    }


    static public ApiDefinition checkApiDefinition(ApiDefinition apiDefinition, String response) throws Exception {
        if (StringUtils.isEmpty(response)) {
            throw new Exception("该请求返回数据为空");
        }
        List<JSONObject> jsonFields = new ArrayList<>();
        String rootPath;
        if (response.startsWith("[")) {
            rootPath = "$[*]";
            JSONArray jsonArray = JSONObject.parseArray(response);
            for (Object o : jsonArray) {
                handleStr(apiDefinition, o.toString(), jsonFields, rootPath);
            }
        } else {
            rootPath = "$";
            handleStr(apiDefinition, response, jsonFields, rootPath);
        }
        apiDefinition.setJsonFields(jsonFields);
        return apiDefinition;
    }


    static private void handleStr(ApiDefinition apiDefinition, String jsonStr, List<JSONObject> objects, String rootPath) {
        if (jsonStr.startsWith("[")) {
            JSONArray jsonArray = JSONObject.parseArray(jsonStr);
            for (Object o : jsonArray) {
                handleStr(apiDefinition, o.toString(), objects, rootPath);
            }
        } else {
            JSONObject jsonObject = JSONObject.parseObject(jsonStr);
            for (String s : jsonObject.keySet()) {
                String value = jsonObject.getString(s);
                if (StringUtils.isNotEmpty(value) && value.startsWith("[")) {
                    JSONArray jsonArray = JSONObject.parseArray(jsonObject.getString(s));
                    List<JSONObject> children = new ArrayList<>();
                    for (Object o : jsonArray) {
                        handleStr(apiDefinition, o.toString(), children, rootPath + "." + s + "[*]");
                    }
                    JSONObject o = new JSONObject();
                    o.put("children", children);
                    o.put("childrenDataType", "LIST");
                    o.put("jsonPath", rootPath + "." + s);
                    setProperty(apiDefinition, o, s);
                    if (!hasItem(apiDefinition, objects, o, null)) {
                        objects.add(o);
                    }
                } else if (StringUtils.isNotEmpty(value) && value.startsWith("{")) {
                    List<JSONObject> children = new ArrayList<>();
                    handleStr(apiDefinition, jsonObject.getString(s), children, rootPath + "." + s);
                    JSONObject o = new JSONObject();
                    o.put("children", children);
                    o.put("childrenDataType", "OBJECT");
                    o.put("jsonPath", rootPath + "." + s);
                    setProperty(apiDefinition, o, s);
                    if (!hasItem(apiDefinition, objects, o, null)) {
                        objects.add(o);
                    }
                } else {
                    JSONObject o = new JSONObject();
                    o.put("children", null);
                    o.put("jsonPath", rootPath + "." + s);
                    setProperty(apiDefinition, o, s);
                    if (!hasItem(apiDefinition, objects, o, StringUtils.isNotEmpty(jsonObject.getString(s))? jsonObject.getString(s) : "")) {
                        JSONArray array = new JSONArray();
                        array.add(StringUtils.isNotEmpty(jsonObject.getString(s))? jsonObject.getString(s) : "");
                        o.put("value", array);
                        objects.add(o);
                    }
                }

            }
        }
    }

    static private void setProperty(ApiDefinition apiDefinition, JSONObject o, String s) {
        o.put("originName", s);
        o.put("name", s);
        o.put("type", "STRING");
        o.put("checked", false);
        o.put("size", 65535);
        o.put("deExtractType", 0);
        o.put("deType", 0);
        o.put("extField", 0);
        o.put("checked", false);
        for (DatasetTableFieldDTO fieldDTO : apiDefinition.getFields()) {
            if (StringUtils.isNotEmpty(o.getString("jsonPath")) && StringUtils.isNotEmpty(fieldDTO.getJsonPath()) && fieldDTO.getJsonPath().equals(o.getString("jsonPath"))) {
                o.put("checked", true);
                o.put("deExtractType", fieldDTO.getDeExtractType());
                o.put("name", fieldDTO.getName());
            }
        }

    }

    static private boolean hasItem(ApiDefinition apiDefinition, List<JSONObject> objects, JSONObject item, String value) {
        boolean has = false;
        for (JSONObject object : objects) {
            JSONObject jsonObject = JSONObject.parseObject(object.toJSONString());
            jsonObject.remove("value");
            jsonObject.remove("id");
            jsonObject.remove("children");
            JSONObject  o = JSONObject.parseObject(item.toJSONString());
            o.remove("children");
            if (object.getString("jsonPath").equals(item.getString("jsonPath")) ) {
                has = true;
                JSONArray array = object.getJSONArray("value");
                if(value != null && array.size() < apiDefinition.getPreviewNum()){
                    array.add(value);
                    object.put("value", array);
                }
                break;
            }
        }

        return has;
    }

    private List<String[]> fetchResult(String result, ApiDefinition apiDefinition) {
        List<String[]> dataList = new LinkedList<>();
        if (StringUtils.isNotEmpty(apiDefinition.getDataPath()) && CollectionUtils.isNotEmpty(apiDefinition.getJsonFields())) {
            List<LinkedHashMap> datas = new ArrayList<>();
            Object object = JsonPath.read(result, apiDefinition.getDataPath());
            if (object instanceof List) {
                datas = (List<LinkedHashMap>) object;
            } else {
                datas.add((LinkedHashMap) object);
            }
            for (LinkedHashMap data : datas) {
                String[] row = new String[apiDefinition.getFields().size()];
                int i = 0;
                for (DatasetTableFieldDTO field : apiDefinition.getFields()) {
                    row[i] = Optional.ofNullable(data.get(field.getOriginName())).orElse("").toString().replaceAll("\n", " ").replaceAll("\r", " ");
                    i++;
                }
                dataList.add(row);
            }
        } else {
            List<String> jsonPaths = apiDefinition.getFields().stream().map(DatasetTableFieldDTO::getJsonPath).collect(Collectors.toList());
            Long maxLenth = 0l;
            List<List<String>> columnDataList = new ArrayList<>();
            for (int i = 0; i < jsonPaths.size(); i++) {
                List<String> datas = new ArrayList<>();
                Object object = JsonPath.read(result, jsonPaths.get(i));
                if (object instanceof List) {
                    datas = (List<String>) object;
                } else {
                    datas.add((String) object);
                }
                maxLenth = maxLenth > datas.size() ? maxLenth : datas.size();
                columnDataList.add(datas);
            }
            for (int i = 0; i < maxLenth; i++) {
                String[] row = new String[apiDefinition.getFields().size()];
                dataList.add(row);
            }
            for (int i = 0; i < columnDataList.size(); i++) {
                for (int j = 0; j < columnDataList.get(i).size(); j++) {
                    dataList.get(j)[i] = String.valueOf(columnDataList.get(i).get(j));
                }
            }
        }
        return dataList;
    }


    private ApiDefinition checkApiDefinition(DatasourceRequest datasourceRequest) throws Exception {
        List<ApiDefinition> apiDefinitionList = new ArrayList<>();
        List<ApiDefinition> apiDefinitionListTemp = new Gson().fromJson(datasourceRequest.getDatasource().getConfiguration(), new TypeToken<List<ApiDefinition>>() {}.getType());
        if (CollectionUtils.isNotEmpty(apiDefinitionListTemp)) {
            for (ApiDefinition apiDefinition : apiDefinitionListTemp) {
                if (apiDefinition.getName().equalsIgnoreCase(datasourceRequest.getTable())) {
                    apiDefinitionList.add(apiDefinition);
                }

            }
        }
        if (CollectionUtils.isEmpty(apiDefinitionList)) {
            throw new Exception("未找到API数据表");
        }
        if (apiDefinitionList.size() > 1) {
            throw new Exception("存在重名的API数据表");
        }
        for (ApiDefinition apiDefinition : apiDefinitionList) {
            if (apiDefinition.getName().equalsIgnoreCase(datasourceRequest.getTable())) {
                return apiDefinition;
            }
        }
        throw new Exception("未找到API数据表");
    }

}
