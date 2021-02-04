import groovy.json.JsonSlurper

import java.nio.charset.StandardCharsets

//def response = sendRequest("GET","https://fatima-jewellery.myshopify.com/admin/api/2021-01/shop.json","",true)
def result = sendRequest("GET","https://fatima-jewellery.myshopify.com/admin/api/2021-01/smart_collections.json","",true).result
def allProductsCollectionID = result.smart_collections[0].id
println allProductsCollectionID
result = sendRequest("GET","https://fatima-jewellery.myshopify.com/admin/api/2021-01/smart_collections/${allProductsCollectionID}.json","",true).result
println result

def sendRequest(String reqMethod, String URL, String message, Boolean failOnError){
    def response = [:]
    def request = new URL(URL).openConnection()
    request.setDoOutput(true)
    request.setRequestMethod(reqMethod)
    String auth = args[0]
    String encoded = Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8))
    request.setRequestProperty("Authorization","Basic $encoded")
    if(!message.isEmpty())
        request.getOutputStream().write(message.getBytes("UTF-8"))
    def getRC = request.getResponseCode()
    response.rc = getRC
    def slurper = new JsonSlurper()
    try {
        if(request.getInputStream().available())
            result = slurper.parseText(request.getInputStream().getText())
        response.result = result
    } catch (Exception ignored) {
        if(failOnError){
            assert false : "Request made to $URL failed.\nResponse code is: $getRC\n${request.getResponseMessage()}\n${request.getErrorStream().getText()}"
        } else{
            response.result = request.getErrorStream().getText()
        }
    }
    return response
}
