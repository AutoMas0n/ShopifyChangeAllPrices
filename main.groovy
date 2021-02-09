import groovy.json.JsonSlurper
import groovy.transform.Field
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutionException

//def response = sendRequest("GET","https://fatima-jewellery.myshopify.com/admin/api/2021-01/shop.json","",true)


@Field def MAX_RETRIES = 5
def result
Retry retry = new Retry()

def allProductsCollectionID
def allProductsHandle
retry.runWithRetries(MAX_RETRIES, () -> {
    result = sendRequest("GET", "https://fatima-jewellery.myshopify.com/admin/api/2021-01/smart_collections.json", "", true).result
    allProductsCollectionID = result.smart_collections[0].id
    allProductsHandle = result.smart_collections[0].handle
})

def productCount
println "$allProductsHandle:$allProductsCollectionID"

while(true) {
    retry.runWithRetries(MAX_RETRIES, () -> {
        productCount = sendRequest("GET", "https://fatima-jewellery.myshopify.com/admin/products/count.json?collection_id=${allProductsCollectionID}", "", true).result.count
    })
    println "Total number of products: $productCount"

    retry.runWithRetries(MAX_RETRIES, () -> {
        result = sendRequest("GET", "https://fatima-jewellery.myshopify.com/admin/api/2021-01/collections/${allProductsCollectionID}/products.json", "", true).result
    })
}


//TODO Find out how to resend same request using request object
//https://stackoverflow.com/questions/12363913/does-httpsurlconnection-getinputstream-makes-automatic-retries
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
    println request.getHeaderField("X-Shopify-Shop-Api-Call-Limit")
    response.rc = getRC
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
                println "RETRYING....."
                if (++count >= maxRetries)
                    throw new Exception("Maximum amount of retries reached, giving up.")
            }
        }
    }

    @Override
    void run() throws ExecutionException {    }
}