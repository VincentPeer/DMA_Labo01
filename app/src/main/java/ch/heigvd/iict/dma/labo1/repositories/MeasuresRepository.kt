package ch.heigvd.iict.dma.labo1.repositories

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Transformations
import ch.heigvd.iict.dma.labo1.models.*
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jdom2.DocType
import org.jdom2.Document
import org.jdom2.Element
import org.jdom2.input.SAXBuilder
import org.jdom2.output.Format
import org.jdom2.output.XMLOutputter
import org.xml.sax.InputSource
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.*
import kotlin.system.measureTimeMillis
import ch.heigvd.iict.dma.labo1.protobuf.MeasuresOuterClass

class MeasuresRepository(private val scope : CoroutineScope,
                         private val dtd : String = "https://mobile.iict.ch/measures.dtd",
                         private val httpUrl : String = "http://mobile.iict.ch/api",
                         private val httpsUrl : String = "https://mobile.iict.ch/api") {

    private val _measures = MutableLiveData(mutableListOf<Measure>())
    val measures = Transformations.map(_measures) { mList -> mList.toList() }

    private val _requestDuration = MutableLiveData(-1L)
    val requestDuration : LiveData<Long> get() = _requestDuration

    fun generateRandomMeasures(nbr: Int = 3) {
        addMeasures(Measure.getRandomMeasures(nbr))
    }

    fun resetRequestDuration() {
        _requestDuration.postValue(-1L)
    }

    fun addMeasure(measure: Measure) {
        addMeasures(listOf(measure))
    }

    fun addMeasures(measures: List<Measure>) {
        val l = _measures.value!!
        l.addAll(measures)
        _measures.postValue(l)
    }

    fun clearAllMeasures() {
        _measures.postValue(mutableListOf())
    }

    fun sendMeasureToServer(encryption : Encryption, compression : Compression, networkType : NetworkType, serialisation : Serialisation) {
        scope.launch(Dispatchers.Default) {

            val url = when (encryption) {
                Encryption.DISABLED -> URL(httpUrl)
                Encryption.SSL -> URL(httpsUrl)
            }

            val elapsed = measureTimeMillis {
                Log.e("SendViewModel", "Implement me !!! Send measures to $url") //TODO
            }
            _requestDuration.postValue(elapsed)

            val connection = withContext(Dispatchers.IO) {
                url.openConnection()
            } as HttpURLConnection

            connection.requestMethod = "POST"
            connection.doOutput = true

            when(serialisation) {
                Serialisation.JSON -> {
                    sendJSONFormat(connection)
                    getJSONResponse(connection)
                }
                Serialisation.XML -> {
                    sendXMLFormat(connection)
                    getXMLResponse(connection)
                }
                Serialisation.PROTOBUF -> {
                    sendPROTOBUFFormat(connection)
                    getPROTOBUFResponse(connection)
                }
            }
        }
    }

    private fun sendJSONFormat(connection: HttpURLConnection) {
        connection.setRequestProperty("Content-Type", "application/json;charset=UTF-8")
        connection.outputStream.bufferedWriter(Charsets.UTF_8).use {
            it.append(Gson().toJson(measures.value))
        }
    }

    private fun getJSONResponse(connection: HttpURLConnection) {
        val responseCode = connection.responseCode
        val json: String
        Log.d("MeasuresRepository", "responseCode for JSON response: $responseCode")
        connection.inputStream.bufferedReader(Charsets.UTF_8).use {
            json = it.readText()
        }

        val response = stringToArray(json, Array<Measure>::class.java)[0]

        // Update local measures status with response measures status
        val l = _measures.value!!
        for (i in 0 until l.size) {
            l[i].status = response[i].status
        }
        // todo : update the list of measures with postValue?
    }

    private fun sendXMLFormat(connection: HttpURLConnection) {
        connection.setRequestProperty("Content-Type", "application/xml;charset=UTF-8")

        val rootElement = Element("measures")

        // For each measures, create a measure element
        _measures.value?.forEach { measure ->
            val measureElement = Element("measure")

            // Define attributes
            measureElement.setAttribute("id", measure.id.toString())
            measureElement.setAttribute("status", measure.status.name)

            // Add content
            measureElement.addContent(Element("type").setText(measure.type.name))
            measureElement.addContent(Element("value").setText(measure.value.toString()))
            measureElement.addContent(Element("date").setText(measure.date.time.toString()))

            // Add measure element to root element
            rootElement.addContent(measureElement)
        }

        // Create the document with the root element and send it
        val document = Document(rootElement)
        document.docType = DocType("measures", dtd)

        // Sending data
        val xmlString = XMLOutputter(Format.getPrettyFormat()).outputString(document)

        OutputStreamWriter(connection.outputStream).use { writer ->
            writer.write(xmlString)
        }

        // Log.d("MeasuresRepository", "document sent : $xmlString")
    }

    private fun getXMLResponse(connection: HttpURLConnection) {
        val responseCode = connection.responseCode
        Log.d("MeasuresRepository", "responseCode for XML response: $responseCode")

        // Stop if any error appeared
        if(responseCode != HttpURLConnection.HTTP_OK) {
            Log.e("MeasuresRepository","XML format error : $responseCode error")
            connection.disconnect()
            return // todo smth better than return?
        }

        val reader = BufferedReader(InputStreamReader(connection.inputStream))
        val responseBuilder = StringBuilder()
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            responseBuilder.append(line)
        }
        val responseString = responseBuilder.toString()

        // Parsing xml response with SAX
        val builder = SAXBuilder()
        builder.setFeature("http://xml.org/sax/features/external-general-entities", false)
//        builder.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        val document = builder.build(InputSource(StringReader(responseString)))

        // Retrieve the elements
        val rootElement = document.rootElement
        val measureElements = rootElement.getChildren("measure")

        // Update measures status
        measureElements.forEach { measureResponseElement ->
            val id = measureResponseElement.getAttributeValue("id").toInt()
            val status = measureResponseElement.getAttributeValue("status")
            _measures.value?.get(id)?.status = Measure.Status.valueOf(status)
        }
    }

    private fun sendPROTOBUFFormat(connection: HttpURLConnection) {
        connection.setRequestProperty("Content-Type", "application/protobuf;charset=UTF-8")
        val measures = MeasuresOuterClass.Measures.newBuilder()
        _measures.value?.forEach { measure ->
            measures.addMeasures(
                MeasuresOuterClass.Measure.newBuilder()
                    .setId(measure.id)
                    .setStatus(MeasuresOuterClass.Status.valueOf(measure.status.name))
                    .setType(measure.type.name)
                    .setValue(measure.value)
                    .setDate(measure.date.timeInMillis)
                    .build()
            )
        }

        // Sending data
        val outputStream = connection.outputStream
        measures.build().writeTo(outputStream)
        outputStream.flush()
        outputStream.close()
    }

    private fun getPROTOBUFResponse(connection: HttpURLConnection) {
        val responseCode = connection.responseCode
        Log.d("MeasuresRepository", "responseCode for PROTOBUF response: $responseCode")

        // Stop if any error appeared
        if(responseCode != HttpURLConnection.HTTP_OK) {
            Log.e("MeasuresRepository","PROTOBUF format error : $responseCode error")
            connection.disconnect()
            return // todo smth better than return?
        }

        val response = MeasuresOuterClass.Measures.parseFrom(connection.inputStream)
        response.measuresList.forEach { measure ->
            _measures.value?.get(measure.id)?.status = Measure.Status.valueOf(measure.status.name)
        }
        println(response)

    }

        // Trick to convert a string to an array of objects
        private fun <T> stringToArray(s: String?, clazz: Class<Array<T>>?): MutableList<Array<T>> {
            val arr = Gson().fromJson(s, clazz)
            return mutableListOf(arr)
        }
    }
