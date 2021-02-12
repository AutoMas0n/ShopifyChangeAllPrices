import groovy.json.JsonSlurper
import groovy.transform.Field
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutionException

@Field def myStore = "https://fatima-jewellery.myshopify.com"
@Field def apiEndpoint = "/admin/api/2021-01/"
@Field def MAX_RETRIES = 10
@Field def MAX_LIMIT = 250
@Field def noOfProductsToDisplay

def result
Retry retry = new Retry()

def allProductsCollectionID
def allProductsHandle
retry.runWithRetries(MAX_RETRIES, () -> {
    result = sendRequest("GET", "$myStore${apiEndpoint}smart_collections.json", "", true).result
    allProductsCollectionID = result.smart_collections[0].id
    allProductsHandle = result.smart_collections[0].handle
})

def productCount
println "$allProductsHandle:$allProductsCollectionID"

retry.runWithRetries(MAX_RETRIES, () -> {
    productCount = sendRequest("GET", "$myStore/admin/products/count.json?collection_id=${allProductsCollectionID}", "", true).result.count
})
println "Total number of products: $productCount"

def products
def productList = []
noOfProductsToDisplay = productCount as int
while(noOfProductsToDisplay > MAX_LIMIT){
//    retry.runWithRetries(MAX_RETRIES, () -> {
        products = sendRequest("GET", "$myStore${apiEndpoint}collections/${allProductsCollectionID}/products.json?limit=$MAX_LIMIT", "", true).result.products
//    })
    productList= products.withDefault(productList.&get)
    noOfProductsToDisplay = noOfProductsToDisplay - MAX_LIMIT
}




retry.runWithRetries(MAX_RETRIES, () -> {
    result = sendRequest("GET", "$myStore${apiEndpoint}collections/${allProductsCollectionID}/products.json?limit=20", "", true)
})

boolean paginate = true
def nextPageLink
def page = result.headers.Link
def pageResponse
while(paginate){
    if(page!=null) {
        page.each {
            nextPageLink = it
            if(nextPageLink.contains(',')) nextPageLink = nextPageLink.split(',')[1]
            nextPageLink = nextPageLink.split("<")[1]
            nextPageLink = nextPageLink.split(">")[0]
            retry.runWithRetries(MAX_RETRIES, () -> {
                pageResponse = sendRequest("GET", "$nextPageLink", "", true)
            })
            pageResponse.result.products.each{
//                productList.add(it.id)
                println it.id
            }
            page = pageResponse.result.headers.Link
            println page
        }
    } else{
        paginate = false
    }
}

println productList.size()

//println result.dump()

//def productID = result.products[0].id
//def productBody = result.products.body_html
//println productID
////println productBody
//
////individual product
//retry.runWithRetries(MAX_RETRIES, () -> {
//    result = sendRequest("GET", "$myStore${apiEndpoint}products/${productID}.json", "", true).result
//})


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
        sleep rateLimit * 1000
        response.retry
    }
    //todo remove this
//    println request.getHeaderField("X-Shopify-Shop-Api-Call-Limit")
    response.rc = getRC
    response.headers = request.getHeaderFields()
    def slurper = new JsonSlurper()
    try {
        if(request.getInputStream().available())
            response.result = slurper.parseText(request.getInputStream().getText())
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
    boolean runWithRetries(int maxRetries, ThrowingTask t) {
        int count = 0;
        while (count < maxRetries) {
            try {
                t.run();
                return true;
            }
            catch (Exception  e) {
                if (++count >= maxRetries)
                    throw new Exception("Maximum amount of retries reached, giving up.")
            }
        }
    }

    @Override
    void run() throws ExecutionException {    }
}