//Usage: main.groovy <USER_TOKEN>:<SECRET_TOKEN>
import groovy.json.JsonBuilder
import groovy.json.JsonSlurper
import groovy.transform.Field
//TODO Implement a logger
//import org.slf4j.Logger
//import org.slf4j.LoggerFactory

import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutionException

//@Field Logger logger = LoggerFactory.getLogger(getClass().name)
@Field def testMode = true //Set to true if you don't want price change to occur
if(testMode) println "########## TEST MODE ENABLED, NO PRICE CHANGE WILL OCCUR ############"

@Field def myStore = "https://fatima-jewellery.myshopify.com"
@Field def apiEndpoint = "/admin/api/2021-01/"
@Field int MAX_RETRIES = 10
@Field def ITEM_PER_PAGE_LIMIT = 250
@Field int noOfRetries = 0
@Field Retry retry = new Retry()
@Field def karatRate = [:]
@Field int rateLimitCount = 0

karatRate."19" = 200
karatRate."18" = 195
karatRate."14" = 165
karatRate."10" = 135

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

def productInventory = []
boolean paginate = true
def nextPageLink

while (paginate) {
    def currentUrl = nextPageLink ?: "$myStore${apiEndpoint}collections/${allProductsCollectionID}/products.json?limit=$ITEM_PER_PAGE_LIMIT"
    pageResponse = simplifyShopifyGet(currentUrl)
    def linkHeader = pageResponse.headers['link']
    //logger.debug "Link header: $linkHeader"

    if (linkHeader) {
        def links = linkHeader[0].split(',').collectEntries {
            def (url, rel) = it.trim().replaceAll(/<|>/, '').split('; ')
            [(rel.split('=')[1].replaceAll("\"", "")) : url]
        }
        nextPageLink = links['next']
        //logger.debug "Next page link: $nextPageLink"
        paginate = nextPageLink != null
    } else {
        paginate = false
    }

    pageResponse.result.products.each { productInventory.add(it) }
    //logger.debug "Collected products count: ${productInventory.size()}"
}

def productIDList = []
productInventory.each{ productIDList.add(it.id) }

if(productIDList.unique().size() != productCount) throw new Exception("Error fetching all product IDs\n ${productIDList.unique().size()} != $productCount")
else println "All unique product IDs accounted for."

def productList = []
productInventory.each{
    if(it.body_html.contains("<meta")){
        def meta = it.body_html.split('>')[0]
        meta = meta.split("<meta")[1].trim()
        productList.add(getMetaData("${it.id}",meta))
    } else {
        println "Missing meta tags: https://fatima-jewellery.myshopify.com/admin/products/${it.id}"
    }
    //Remove Products with multiple Variants from list
    String invID = it.id
    def ids = productList.id
    if(it.options.size() > 1) productList.remove(ids.indexOf(invID))
}

def productsToRemove = []
productList.each{
    double weight = "${it.weight}".toDouble()

    if(!it.metal.contains('gold')) {
        productsToRemove.add(it) //Remove products that aren't gold
        println "Not gold: "
    } else if(weight < 1){
        productsToRemove.add(it) //Remove products that are below 1 gram
    }
}
println productList.size()
productList.removeAll(productsToRemove)

println "Performing price change for a total of ${productList.size()} gold products"

int progressCount=1
productList.each{
    long idVal = Long.valueOf("${it.id}")
    if(it.metal.contains("gold")) {
//        if (idVal == 4874577412176) {
        int newPrice = "${it.weight}".toDouble() * karatRate."${it.karat}".toInteger()
        int priceAlter = Math.round(newPrice/10.0) * 10
        if(priceAlter<newPrice) priceAlter += 9
        else if (priceAlter>newPrice) priceAlter -= 1

        def json = new JsonBuilder()
        def put = json {
            product {
                id idVal
            }
        }
        def allVariants = simplifyShopifyPut("$myStore/admin/api/2020-04/products/${idVal}.json", json.toPrettyString()).result.product.variants
        print "$idVal from: \$" + allVariants[0].price + " --> \$${priceAlter}  (${it.weight} x " + karatRate."${it.karat}".toInteger() + ")"
        def variantID = allVariants[0].id
        put = json {
            product {
                id idVal
                variants(collect() { [id: variantID, price: priceAlter] })
                //if you need multiple variants more coding required)
            }
        }
        if(!testMode) simplifyShopifyPut("$myStore/admin/api/2020-04/products/${idVal}.json", json.toPrettyString()).result.product.variants
        print "...done ($progressCount/${productList.size()})\n"
        progressCount++
    }
}

//Verified this not happening for products less than 1g

println "RETRY COUNT = $noOfRetries"
println "RATE LIMIT COUNT = $rateLimitCount"

def getMetaData(String id, String meta){
    def data = meta.split(" ")
    def map = [:]
    data.each{
        map.put(it.split('=')[0],it.split('=')[1].replaceAll('"',""))
    }
    map.put("id",id)
    return map
}

def simplifyShopifyGet(String endpoint){
    def result
    noOfRetries += retry.runWithRetries(MAX_RETRIES, () -> {
        result = sendRequest("GET", "$endpoint", "", true)
    })
    return result
}

def simplifyShopifyPut(String endpoint, String message){
    def result
    noOfRetries += retry.runWithRetries(MAX_RETRIES, () -> {
        result = sendRequest("PUT", "$endpoint", message, true)
    })
    return result
}

def sendRequest(String reqMethod, String URL, String message, Boolean failOnError){
    //logger.debug "Request Method: $reqMethod"
    //logger.debug "Request URL: $URL"
    //logger.debug "Request Body: $message"
    def response = [:]
    def request = new URL(URL).openConnection()
    request.setDoOutput(true)
    request.setRequestMethod(reqMethod)
    String auth = args[0]
    String encoded = Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8))
    request.setRequestProperty("Authorization","Basic $encoded")
    request.setRequestProperty("Content-Type", "application/json")
    if(!message.isEmpty())
        request.getOutputStream().write(message.getBytes("UTF-8"))
    def getRC = request.getResponseCode()
    def rateLimit = request.getHeaderField("Retry-After")
    if(rateLimit != null){
        rateLimitCount++
        //logger.debug "########################## RATE WAS LIMITED #####################################"
        sleep (rateLimit * 10000)
        sendRequest(reqMethod,URL,message,failOnError)
    }
    response.rc = getRC
    response.headers = request.getHeaderFields()
    //logger.debug "Response headers: ${response.headers}"
    def slurper = new JsonSlurper()
    try {
        if (request.getInputStream()) {
            request.getInputStream().withCloseable { stream ->
                response.result = slurper.parseText(stream.text)
            }
        } else {
            throw new Exception("failed to get result from inputStream, treating as a failure")
        }
    } catch (Exception e) {
        def errorStream = request.getErrorStream()?.getText() ?: 'No error stream available'
        println "Request failed with exception: $e"
        println "Response code is: $getRC"
        println "Response message: ${request.getResponseMessage()}"
        println "Error stream: $errorStream"
        if (failOnError) {
            assert false : "Request made to $URL failed.\nResponse code is: $getRC\n${request.getResponseMessage()}\n$errorStream\nException: $e"
        } else {
            response.result = errorStream
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
