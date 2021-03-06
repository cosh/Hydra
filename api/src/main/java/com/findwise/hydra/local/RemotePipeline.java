package com.findwise.hydra.local;

import java.io.IOException;
import java.util.Map;

import org.apache.http.HttpException;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.util.EntityUtils;

import com.findwise.hydra.common.InternalLogger;
import com.findwise.hydra.common.JsonException;
import com.findwise.hydra.common.SerializationUtils;
import com.findwise.tools.HttpConnection;

public class RemotePipeline {
	public static final String GET_DOCUMENT_URL = "getDocument";
	public static final String WRITE_DOCUMENT_URL = "writeDocument";
	public static final String RELEASE_DOCUMENT_URL = "releaseDocument";
	public static final String PROCESSED_DOCUMENT_URL = "processedDocument";
	public static final String PENDING_DOCUMENT_URL = "pendingDocument";
	public static final String DISCARDED_DOCUMENT_URL = "discardedDocument";
	public static final String GET_PROPERTIES_URL = "getProperties";
	
	public static final String STAGE_PARAM = "stage";
	public static final String RECURRING_PARAM = "recurring";
	public static final String NORELEASE_PARAM = "norelease";
	public static final String PARTIAL_PARAM = "partial";
	
	public static final int DEFAULT_PORT = 12001;
	public static final String DEFAULT_HOST = "127.0.0.1";
	
	private HttpConnection core;
	
	private boolean keepLock;
	
	private String getUrl;
	private String getRecurringUrl;
	private String writeUrl;
	private String releaseUrl;
	private String processedUrl;
	private String pendingUrl;
	private String discardedUrl;
	private String propertyUrl;
	
	private LocalDocument currentDocument;
	
	/**
	 * Calls RemotePipeline(String, int, String) with default values for 
	 * hostName (RemotePipeline.DEFAULT_HOST) and port (RemotePipeline.DEFAULT_PORT).
	 * 
	 * @param stageName
	 */
	public RemotePipeline(String stageName) {
		this(DEFAULT_HOST, DEFAULT_PORT, stageName);
	}
	
	public RemotePipeline(String hostName, int port, String stageName) {
		getUrl = "/"+GET_DOCUMENT_URL+"?"+STAGE_PARAM+"="+stageName;
		writeUrl = "/"+WRITE_DOCUMENT_URL+"?"+STAGE_PARAM+"="+stageName;
		releaseUrl = "/"+RELEASE_DOCUMENT_URL+"?"+STAGE_PARAM+"="+stageName;
		processedUrl = "/"+PROCESSED_DOCUMENT_URL+"?"+STAGE_PARAM+"="+stageName;
		pendingUrl = "/"+PENDING_DOCUMENT_URL+"?"+STAGE_PARAM+"="+stageName;
		discardedUrl = "/"+DISCARDED_DOCUMENT_URL+"?"+STAGE_PARAM+"="+stageName;
		propertyUrl = "/"+GET_PROPERTIES_URL+"?"+STAGE_PARAM+"="+stageName;
		
		getRecurringUrl = getUrl+"&"+RECURRING_PARAM+"=1";
		
		keepLock = false;
		
		core = new HttpConnection(hostName, port);
	}
	
	/**
	 * Non-recurring, use this in all known cases except for in an output node.
	 * 
	 * The fetched document will be tagged with the name of the stage which is
	 * used to execute getDocumebt.
	 */
	public LocalDocument getDocument(LocalQuery query) throws IOException, HttpException, JsonException {
		return getDocument(query, false);
	}

	public LocalDocument getDocument(LocalQuery query, boolean recurring) throws IOException, HttpException,
			JsonException {
		HttpResponse response;
		if(recurring) {
			response = core.post(getRecurringUrl, query.toJson());
		}
		else {
			response = core.post(getUrl, query.toJson());
		}
		
		if (response.getStatusLine().getStatusCode() == HttpStatus.SC_OK) {
			LocalDocument ld = new LocalDocument(EntityUtils.toString(response.getEntity()));
			InternalLogger.debug("Received document with ID " + ld.getID());
			currentDocument = ld;
			return ld;
		} else if (response.getStatusLine().getStatusCode() == HttpStatus.SC_NOT_FOUND) {
			InternalLogger.debug("No document found matching query");
			EntityUtils.consume(response.getEntity());
			return null;
		} else {
			logUnexpected(response);
			return null;
		}
	}
	
	/**
	 * Releases the most recently read document back to the pipeline
	 * 
	 * @return true if there was a document to release
	 * @throws HttpException 
	 * @throws IOException 
	 */
	public boolean releaseLastDocument() throws IOException, HttpException{
		if(currentDocument==null) {
			InternalLogger.debug("There is no document to release...");
			return false;
		}
		HttpResponse response = core.post(releaseUrl, currentDocument.contentFieldsToJson(null));
		currentDocument = null;
		if(response.getStatusLine().getStatusCode()==HttpStatus.SC_OK) {
			EntityUtils.consume(response.getEntity());
			return true;
		}
		
		logUnexpected(response);
		
		return false;
	}
	
	private static void logUnexpected(HttpResponse response) throws IOException {
		InternalLogger.error("Node gave an unexpected response: "+response.getStatusLine());
		InternalLogger.error("Message: "+EntityUtils.toString(response.getEntity()));
	}

	/**
	 * Calling this will allow you save a document. Note, this will need to be reset every time you wish to partially save a document.
	 */
	public void keepLock() {
		keepLock = true;
	}
	
	/**
	 * Writes all outstanding updates to the last document fetched from the pipeline.
	 */
	public boolean saveCurrentDocument() throws IOException, HttpException, JsonException {
		boolean keepingLock = keepLock;
		if (currentDocument == null) {
			InternalLogger.error("There is no document to write.");
			return false;
		}
		boolean x = save(currentDocument);
		if (x && !keepingLock) {
			currentDocument = null;
		}

		return x;
	}

	/**
	 * Writes an entire document to the pipeline. Use is discouraged, try using save(..) whenever possible.
	 */
	public boolean saveFull(LocalDocument d) throws IOException, HttpException, JsonException {
		boolean res = save(d, false);
		if(res) {
			d.markSynced();
			keepLock=false;
		}
		return res;
	}
	
	/**
	 * Writes all outstanding updates to the document since it was initialized.
	 */
	public boolean save(LocalDocument d) throws IOException, HttpException, JsonException {
		boolean res = save(d, true);
		if(res) {
			d.markSynced();
			keepLock=false;
		}
		return res;
	}
	
	private boolean save(LocalDocument d, boolean partialUpdate) throws IOException, HttpException, JsonException {
		boolean hasId = d.getID()!=null;
		String s;
		if(partialUpdate) {
			s = d.modifiedFieldsToJson();
		}
		else {
			s = d.toJson();
		}
		HttpResponse response = core.post(getWriteUrl(partialUpdate), s);
		if(response.getStatusLine().getStatusCode()==HttpStatus.SC_OK) {
			if(!hasId) {
				LocalDocument updated = new LocalDocument(EntityUtils.toString(response.getEntity()));
				d.putAll(updated);
			}
			else {
				EntityUtils.consume(response.getEntity());
			}
			return true;
		}
		
		logUnexpected(response);
		
		return false;
	}
	
	public boolean markPending(LocalDocument d) throws IOException, HttpException {
		HttpResponse response = core.post(pendingUrl, d.contentFieldsToJson(null));
		if(response.getStatusLine().getStatusCode()==HttpStatus.SC_OK) {
			EntityUtils.consume(response.getEntity());
		
			return true;
		}
		
		logUnexpected(response);
		
		return false;
	}
	
	public boolean markProcessed(LocalDocument d) throws IOException, HttpException {
		HttpResponse response = core.post(processedUrl, d.contentFieldsToJson(null));
		if(response.getStatusLine().getStatusCode()==HttpStatus.SC_OK) {
			EntityUtils.consume(response.getEntity());
		
			return true;
		}
		
		logUnexpected(response);
		
		return false;
	}
	
	public boolean markDiscarded(LocalDocument d) throws IOException, HttpException {
		HttpResponse response = core.post(discardedUrl, d.contentFieldsToJson(null));
		if(response.getStatusLine().getStatusCode()==HttpStatus.SC_OK) {
			EntityUtils.consume(response.getEntity());
		
			return true;
		}
		
		logUnexpected(response);
		
		return false;
	}
	
	private String getWriteUrl(boolean partialUpdate) {
		String s = writeUrl;
		if(keepLock) {
			s+="&"+NORELEASE_PARAM+"=1";
		}
		else {
			s+="&"+NORELEASE_PARAM+"=0";
		}
		if(partialUpdate) {
			s+="&"+PARTIAL_PARAM+"=1";
		}
		else {
			s+="&"+PARTIAL_PARAM+"=0";
		}
		return s;
	}
	
	public Map<String, Object> getProperties() throws JsonException, IOException, HttpException {
		HttpResponse response = core.get(propertyUrl);
		
		if (response.getStatusLine().getStatusCode() == HttpStatus.SC_OK) {
			Map<String, Object> map = SerializationUtils.fromJson(EntityUtils.toString(response.getEntity()));
			InternalLogger.debug("Successfully retrieved propertyMap with " + map.size()+" entries");
			return map;
		} else if (response.getStatusLine().getStatusCode() == HttpStatus.SC_NOT_FOUND) {
			InternalLogger.debug("No document found matching query");
			EntityUtils.consume(response.getEntity());
			return null;
		} else {
			logUnexpected(response);
			return null;
		}
	}
}
