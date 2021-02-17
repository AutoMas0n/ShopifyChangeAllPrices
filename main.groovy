import groovy.json.JsonSlurper
import groovy.transform.Field
import groovy.xml.DOMBuilder
import groovy.xml.slurpersupport.GPathResult

import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutionException

@Field def myStore = "https://fatima-jewellery.myshopify.com"
@Field def apiEndpoint = "/admin/api/2021-01/"
@Field def MAX_RETRIES = 10
@Field def ITEM_PER_PAGE_LIMIT = 250
@Field def noOfRetries = 0
@Field Retry retry = new Retry()

def result
def allProductsCollectionID
def allProductsHandle
result = simplifyShopifyGet("$myStore${apiEndpoint}smart_collections.json").result
allProductsCollectionID = result.smart_collections[0].id
allProductsHandle = result.smart_collections[0].handle

def productCount
println "$allProductsHandle:$allProductsCollectionID"

productCount = simplifyShopifyGet("$myStore/admin/products/count.json?collection_id=${allProductsCollectionID}").result.count
println "Total number of products: $productCount"

println "Getting all Product IDs..."
def pageResponse
pageResponse = simplifyShopifyGet("$myStore${apiEndpoint}collections/${allProductsCollectionID}/products.json?limit=$ITEM_PER_PAGE_LIMIT")
def productList = []
boolean paginate = true
def nextPageLink
def previousPageList = []
def headerLink = pageResponse.headers.Link
pageResponse.result.products.each { productList.add(it.id) }
while(paginate){
    if(headerLink == null){
        paginate = false
    } else {
        headerLink.each {
            nextPageLink = it
            if (nextPageLink.contains("rel=\"next\"")) {
                if (nextPageLink.contains(',')) nextPageLink = nextPageLink.split(',')[1]
                nextPageLink = nextPageLink.split("<")[1]
                nextPageLink = nextPageLink.split(">")[0]
                if(previousPageList!=null && !previousPageList.contains(nextPageLink)) previousPageList.add(nextPageLink)
                else throw new Exception("Error during pagination: Duplicate page URL was found during parsing.")
                pageResponse = simplifyShopifyGet("$nextPageLink")
                pageResponse.result.products.each { productList.add(it.id) }
                headerLink = pageResponse.headers.Link
            } else {
                paginate = false
            }
        }
    }
}

if(productList.unique().size() != productCount) throw new Exception("Error fetching all product IDs\n ${productList.unique().size()} != $productCount")
else println "All unique product IDs accounted for."

println "Fetching product details for $productCount products.."
def products = [:]
def testCount = 0
//TODO Progress bar https://github.com/ctongfei/progressbar
for (it in productList) {
    result = simplifyShopifyGet("$myStore${apiEndpoint}products/${it}.json").result
    products.put(it,result)
    testCount++
    println testCount
    if(testCount>0) break
}
//Every product is stored in products map object
//TODO verify products.size() is the same as productCount
//if(products.size() != (productCount as int)) throw new Exception("Could not fetch all products")

//TODO account for products without meta an display URL to console + file
//TODO does this really need to be iterated again?
products.each{
    println it.getValue().product.title
    String body_html = it.getValue().product.body_html
    if(body_html.contains("<meta")){
        def meta = body_html.split('>')[0]
        meta = meta.split("<meta")[1].trim()
        def metaMap = getMetaData(meta)
        println metaMap
    }
}

//TODO is this happening for products less than 1 g?

println "RETRY COUNT = $noOfRetries"

def getMetaData(String meta){
    def data = meta.split(" ")
    def map = [:]
    data.each{
        map.put(it.split('=')[0],it.split('=')[1].replaceAll('"',""))
    }
    return map
}

def simplifyShopifyGet(String endpoint){
    def result
    noOfRetries += retry.runWithRetries(MAX_RETRIES, () -> {
        result = sendRequest("GET", "$endpoint", "", true)
    })
    return result
}

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
    def rateLimit = request.getHeaderField("Retry-After")
    if(rateLimit != null){
        println "########################## RATE WAS LIMITED #####################################"
        sleep (rateLimit * 10000)
        sendRequest(reqMethod,URL,message,failOnError)
    }
    response.rc = getRC
    response.headers = request.getHeaderFields()
    def slurper = new JsonSlurper()
    try {
        if(request.getInputStream().available())
            response.result = slurper.parseText(request.getInputStream().getText())
        else
            throw new Exception("failed to get result from inputStream, treating as a failure")
    } catch (Exception e) {
        if(failOnError){
            assert false : "Request made to $URL failed.\nResponse code is: $getRC\n${request.getResponseMessage()}\n" +
                    "${request.getErrorStream().getText()}\nException: $e"
        } else{
            response.result = request.getErrorStream().getText()
        }
    }
    return response
}

interface ThrowingTask {
    void run() throws ExecutionException;
}

class Retry implements  ThrowingTask {
    int runWithRetries(int maxRetries, ThrowingTask t) {
        int count = 0;
        while (count < maxRetries) {
            try {
                t.run();
                return count;
            }
            catch (Exception  e) {
                if (++count >= maxRetries)
                    throw new Exception("Maximum amount of retries reached, giving up.")
                sleep 100 * count //Add a delay as retries fail
            }
        }
    }

    @Override
    void run() throws ExecutionException {    }
}