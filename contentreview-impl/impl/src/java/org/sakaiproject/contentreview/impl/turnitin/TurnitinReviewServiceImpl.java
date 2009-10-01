/**********************************************************************************
 * $URL$
 * $Id$
 ***********************************************************************************
 *
 * Copyright (c) 2006 Sakai Foundation
 *
 * Licensed under the Educational Community License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.osedu.org/licenses/ECL-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 **********************************************************************************/
package org.sakaiproject.contentreview.impl.turnitin;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.ProtocolException;
import java.net.Proxy;
import java.net.SocketAddress;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TimeZone;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.poi.hpsf.SummaryInformation;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.sakaiproject.api.common.edu.person.SakaiPerson;
import org.sakaiproject.api.common.edu.person.SakaiPersonManager;
import org.sakaiproject.authz.api.SecurityService;
import org.sakaiproject.component.api.ServerConfigurationService;
import org.sakaiproject.content.api.ContentHostingService;
import org.sakaiproject.content.api.ContentResource;
import org.sakaiproject.contentreview.dao.impl.ContentReviewDao;
import org.sakaiproject.contentreview.exception.QueueException;
import org.sakaiproject.contentreview.exception.ReportException;
import org.sakaiproject.contentreview.exception.SubmissionException;
import org.sakaiproject.contentreview.exception.TransientSubmissionException;
import org.sakaiproject.contentreview.impl.hbm.BaseReviewServiceImpl;
import org.sakaiproject.contentreview.model.ContentReviewItem;
import org.sakaiproject.db.api.SqlReader;
import org.sakaiproject.db.api.SqlService;
import org.sakaiproject.entity.api.Entity;
import org.sakaiproject.entity.api.EntityManager;
import org.sakaiproject.entity.api.EntityProducer;
import org.sakaiproject.entity.api.Reference;
import org.sakaiproject.entity.api.ResourceProperties;
import org.sakaiproject.exception.IdUnusedException;
import org.sakaiproject.exception.PermissionException;
import org.sakaiproject.exception.ServerOverloadException;
import org.sakaiproject.exception.TypeException;
import org.sakaiproject.turnitin.util.TurnitinAPIUtil;
import org.sakaiproject.user.api.User;
import org.sakaiproject.user.api.UserDirectoryService;
import org.sakaiproject.user.api.UserNotDefinedException;
import org.w3c.dom.CharacterData;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

public class TurnitinReviewServiceImpl extends BaseReviewServiceImpl {


	private static final Log log = LogFactory
	.getLog(TurnitinReviewServiceImpl.class);


	private static final String SERVICE_NAME="Turnitin";

	private String aid = null;

	private String said = null;

	private String secretKey = null;

	private String apiURL = "https://www.turnitin.com/api.asp?";

	private String proxyHost = null;

	private String proxyPort = null;

	final static long LOCK_PERIOD = 12000000;

	private String defaultInstructorEmail = null;

	private String defaultInstructorFName = null;

	private String defaultInstructorLName = null;

	private String defaultInstructorPassword = null;

	private boolean useSourceParameter = false;

	public boolean isUseSourceParameter() {
		return useSourceParameter;
	}

	public void setUseSourceParameter(boolean useSourceParameter) {
		this.useSourceParameter = useSourceParameter;
	}

	private int sendNotifications = 0;

	private Long maxRetry = null;

	private int TII_MAX_FILE_SIZE = 10995116;

	// Proxy if set
	private Proxy proxy = null; 

	//note that the assignment id actually has to be unique globally so use this as a prefix
	// eg. assignid = defaultAssignId + siteId
	private String defaultAssignId = null;

	private String defaultClassPassword = null;

	//private static final String defaultInstructorId = defaultInstructorFName + " " + defaultInstructorLName;
	private String defaultInstructorId = null;

	/**
	 *  Setters
	 */

	private ServerConfigurationService serverConfigurationService; 

	public void setServerConfigurationService (ServerConfigurationService serverConfigurationService) {
		this.serverConfigurationService = serverConfigurationService;
	}

	private EntityManager entityManager;

	public void setEntityManager(EntityManager en){
		this.entityManager = en;
	}

	private ContentHostingService contentHostingService;

	public void setContentHostingService(
			ContentHostingService contentHostingService) {
		this.contentHostingService = contentHostingService;
	}


	private SakaiPersonManager sakaiPersonManager;

	public void setSakaiPersonManager(SakaiPersonManager s) {
		this.sakaiPersonManager = s;
	}


	//Should the service prefer the system profile email address for users if set?
	private boolean preferSystemProfileEmail;

	public void setPreferSystemProfileEmail(boolean b) {
		preferSystemProfileEmail = b;
	}

	private ContentReviewDao dao;

	public void setDao(ContentReviewDao dao) {
		super.setDao(dao);
		this.dao = dao;
	}


	private UserDirectoryService userDirectoryService;

	public void setUserDirectoryService(
			UserDirectoryService userDirectoryService) {
		super.setUserDirectoryService(userDirectoryService);
		this.userDirectoryService = userDirectoryService;
	}

	private SecurityService securityService;
	public void setSecurityService(SecurityService ss) {
		securityService = ss;
	}

	private SqlService sqlService;
	public void setSqlService(SqlService sql) {
		sqlService = sql;
	}

	/**
	 * Place any code that should run when this class is initialized by spring
	 * here
	 */

	public void init() {

		log.info("init()");

		proxyHost = serverConfigurationService.getString("turnitin.proxyHost"); 

		proxyPort = serverConfigurationService.getString("turnitin.proxyPort");



		if (!"".equals(proxyHost) && !"".equals(proxyPort)) {
			try {
				SocketAddress addr = new InetSocketAddress(proxyHost, new Integer(proxyPort).intValue());
				proxy = new Proxy(Proxy.Type.HTTP, addr);
				log.debug("Using proxy: " + proxyHost + " " + proxyPort);
			} catch (NumberFormatException e) {
				log.debug("Invalid proxy port specified: " + proxyPort);
			}
		} else if (System.getProperty("http.proxyHost") != null && !System.getProperty("http.proxyHost").equals("")) {
			try {
				SocketAddress addr = new InetSocketAddress(System.getProperty("http.proxyHost"), new Integer(System.getProperty("http.proxyPort")).intValue());
				proxy = new Proxy(Proxy.Type.HTTP, addr);
				log.debug("Using proxy: " + System.getProperty("http.proxyHost") + " " + System.getProperty("http.proxyPort"));
			} catch (NumberFormatException e) {
				log.debug("Invalid proxy port specified: " + System.getProperty("http.proxyPort"));
			}
		}

		aid = serverConfigurationService.getString("turnitin.aid");

		said = serverConfigurationService.getString("turnitin.said");

		secretKey = serverConfigurationService.getString("turnitin.secretKey");

		apiURL = serverConfigurationService.getString("turnitin.apiURL","https://www.turnitin.com/api.asp?");



		defaultInstructorEmail = serverConfigurationService.getString("turnitin.defaultInstructorEmail");

		defaultInstructorFName = serverConfigurationService.getString("turnitin.defaultInstructorFName");

		defaultInstructorLName = serverConfigurationService.getString("turnitin.defaultInstructorLName");

		defaultInstructorPassword = serverConfigurationService.getString("turnitin.defaultInstructorPassword");

		useSourceParameter = serverConfigurationService.getBoolean("turnitin.useSourceParameter", false);

		if  (!serverConfigurationService.getBoolean("turnitin.sendnotifations", true)) 
			sendNotifications = 1;

		//note that the assignment id actually has to be unique globally so use this as a prefix
		// assignid = defaultAssignId + siteId
		defaultAssignId = serverConfigurationService.getString("turnitin.defaultAssignId");;

		defaultClassPassword = serverConfigurationService.getString("turnitin.defaultClassPassword","changeit");;

		//private static final String defaultInstructorId = defaultInstructorFName + " " + defaultInstructorLName;
		defaultInstructorId = serverConfigurationService.getString("turnitin.defaultInstructorId","admin");

		maxRetry = Long.valueOf(serverConfigurationService.getInt("turnitin.maxRetry",100));

		TII_MAX_FILE_SIZE = serverConfigurationService.getInt("turnitin.maxFileSize",10995116);

		if (!useSourceParameter) {
			if (serverConfigurationService.getBoolean("turnitin.updateAssingments", false))
				doAssignments();
		}

	}


	public String getServiceName() {
		return this.SERVICE_NAME;
	}



	public String getIconUrlforScore(Long score) {

		String urlBase = "/sakai-contentreview-tool/images/score_";
		String suffix = ".gif";

		if (score.equals(Long.valueOf(0))) {
			return urlBase + "blue" + suffix;
		} else if (score.compareTo(Long.valueOf(25)) < 0 ) {
			return urlBase + "green" + suffix;
		} else if (score.compareTo(Long.valueOf(50)) < 0  ) {
			return urlBase + "yellow" + suffix;
		} else if (score.compareTo(Long.valueOf(75)) < 0 ) {
			return urlBase + "orange" + suffix;
		} else {
			return urlBase + "red" + suffix;
		}

	}


	public boolean isAcceptableContent(ContentResource resource) {
		//for now we accept all content
		// TODO: Check against content types accepted by Turnitin
		/*
		 * Turnitin currently accepts the following file types for submission: MS Word (.doc), WordPerfect (.wpd), PostScript (.eps), Portable Document Format (.pdf), HTML (.htm), Rich Text (.rtf) and Plain Text (.txt)
		 * text/plain
		 * text/html
		 * application/msword
		 * application/msword
		 * application/postscript
		 */

		String mime = resource.getContentType();
		log.debug("Got a content type of " + mime);

		Boolean fileTypeOk = false;
		if ((mime.equals("text/plain") || mime.equals("text/html") || mime.equals("application/msword") || 
				mime.equals("application/postscript") || mime.equals("application/pdf") || mime.equals("text/rtf")) ) {
			fileTypeOk =  true;
			log.debug("FileType matches a known mime");
		}

		//as mime's can be tricky check the extensions
		if (!fileTypeOk) {
			ResourceProperties resourceProperties = resource.getProperties();
			String fileName = resourceProperties.getProperty(resourceProperties.getNamePropDisplayName());
			if (fileName.indexOf(".")>0) {

				String extension = fileName.substring(fileName.lastIndexOf("."));
				log.debug("file has an extension of " + extension);
				if (extension.equals(".doc") || extension.equals(".wpd") || extension.equals(".eps") 
						||  extension.equals(".txt") || extension.equals(".htm") || extension.equals(".html") 
						|| extension.equals(".pdf") || extension.equals(".docx") || ".rtf".equals(extension))
					fileTypeOk = true;

			} else {
				//we don't know what this is so lets submit it anyway
				fileTypeOk = true;
			}
		}

		if (!fileTypeOk) {
			return false;
		}

		//TODO: if file is too big reject here 10.48576 MB

		if (resource.getContentLength() > TII_MAX_FILE_SIZE) {
			log.debug("File is too big: " + resource.getContentLength());
			return false;
		}

		//if this is a msword type file we can check the legth
		if (isMsWordDoc(resource)) {
			if (wordDocLength(resource) < 20) {
				return false;
			}
		}


		return true;
	}

	private int wordDocLength(ContentResource resource) {
		if (!serverConfigurationService.getBoolean("tii.checkWordLength", false))
			return 100;

		try {
			POIFSFileSystem pfs = new POIFSFileSystem(resource.streamContent());
			HWPFDocument doc = new HWPFDocument(pfs);
			SummaryInformation dsi = doc.getSummaryInformation();
			int count = dsi.getWordCount();
			log.debug("got a count of " + count);
			//if this == 0 then its likely that something went wrong -poi couldn't read it
			if (count == 0)
				return 100;
			return count;
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (ServerOverloadException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		//in case we can't read this lets err on the side of caution
		return 100;
	}

	private boolean isMsWordDoc(ContentResource resource) {
		String mime = resource.getContentType();
		log.debug("Got a content type of " + mime);


		if (mime.equals("application/msword")) {
			log.debug("FileType matches a known mime");
			return true;
		}

		ResourceProperties resourceProperties = resource.getProperties();
		String fileName = resourceProperties.getProperty(resourceProperties.getNamePropDisplayName());
		if (fileName.contains(".")) {
			String extension = fileName.substring(fileName.lastIndexOf("."));
			log.debug("file has an extension of " + extension);
			if (extension.equals(".doc") || extension.equals(".docx") || ".rtf".equals(extension)) {
				return true;
			}
		} else {
			//if the file has no extension we assume its a doc
			return true;
		}

		return false;
	}

	/* (non-Javadoc)
	 * @see org.sakaiproject.contentreview.service.ContentReviewService#getReviewReportInstructor(java.lang.String)
	 */
	public String getReviewReportInstructor(String contentId) throws QueueException, ReportException {
		List matchingItems = dao.findByExample(new ContentReviewItem(contentId));
		if (matchingItems.size() == 0) {
			log.debug("Content " + contentId + " has not been queued previously");
			throw new QueueException("Content " + contentId + " has not been queued previously");
		}

		if (matchingItems.size() > 1)
			log.debug("More than one matching item found - using first item found");

		// check that the report is available
		// TODO if the database record does not show report available check with
		// turnitin (maybe)

		ContentReviewItem item = (ContentReviewItem) matchingItems.iterator().next();
		if (item.getStatus().compareTo(ContentReviewItem.SUBMITTED_REPORT_AVAILABLE_CODE) != 0) {
			log.debug("Report not available: " + item.getStatus());
			throw new ReportException("Report not available: " + item.getStatus());
		}


		// report is available - generate the URL to display

		String oid = item.getExternalId();
		String fid = "6";
		String fcmd = "1";
		String encrypt = "0";
		String diagnostic = "0";
		String cid = item.getSiteId();
		String assignid = defaultAssignId + item.getSiteId();

		String uem;
		String ufn;
		String uln;
		String utp;
		String uid;

		uem = getProvisionerEmail();
		ufn = getProvisionerFName();
		uln = getProvisionerLName();
		utp = "2";
		uid = getProvisionerUserID();

		String gmtime = getGMTime();
		
		Map params = TurnitinAPIUtil.packMap(null, 
				"fid", fid,
				"fcmd", fcmd,
				"assignid", assignid,
				"uid", uid,
				"cid", cid,
				"encrypt", encrypt,
				"aid", aid,
				"said", said,
				"diagnostic", diagnostic,
				"oid", oid,
				"uem", uem,
				"ufn", ufn,
				"uln", uln,
				"utp", utp,
				"gmtime", gmtime
				
		);
		
		if (useSourceParameter) {
			params = TurnitinAPIUtil.packMap(params, "src", "9");
		}

		return TurnitinAPIUtil.buildTurnitinURL(apiURL, params, secretKey);
	}

	public String getReviewReportStudent(String contentId) throws QueueException, ReportException {
		List<ContentReviewItem> matchingItems = dao.findByExample(new ContentReviewItem(contentId));
		if (matchingItems.size() == 0) {
			log.debug("Content " + contentId + " has not been queued previously");
			throw new QueueException("Content " + contentId + " has not been queued previously");
		}

		if (matchingItems.size() > 1)
			log.debug("More than one matching item found - using first item found");

		// check that the report is available
		// TODO if the database record does not show report available check with
		// turnitin (maybe)

		ContentReviewItem item = (ContentReviewItem) matchingItems.iterator().next();
		if (item.getStatus().compareTo(ContentReviewItem.SUBMITTED_REPORT_AVAILABLE_CODE) != 0) {
			log.debug("Report not available: " + item.getStatus());
			throw new ReportException("Report not available: " + item.getStatus());
		}


		// report is available - generate the URL to display

		String oid = item.getExternalId();
		String fid = "6";
		String fcmd = "1";
		String encrypt = "0";
		String diagnostic = "0";
		String cid = item.getSiteId();
		String assignid = defaultAssignId + item.getSiteId();

		String uem;
		String ufn;
		String uln;
		String utp;
		String uid;


		User user = userDirectoryService.getCurrentUser();
		uem = user.getEmail();
		ufn = getUserFirstName(user);
		uln = user.getLastName();
		uid = item.getUserId();
		utp = "1";

		String gmtime = getGMTime();

		Map params = TurnitinAPIUtil.packMap(null, 
				"fid", fid,
				"fcmd", fcmd,
				"assignid", assignid,
				"uid", uid,
				"cid", cid,
				"encrypt", encrypt,
				"aid", aid,
				"said", said,
				"diagnostic", diagnostic,
				"oid", oid,
				"uem", uem,
				"ufn", ufn,
				"uln", uln,
				"utp", utp,
				"gmtime", gmtime
				
		);
		
		if (useSourceParameter) {
			params = TurnitinAPIUtil.packMap(params, "src", "9");
		}
		
		String reportURL = apiURL;

		return TurnitinAPIUtil.buildTurnitinURL(apiURL, params, secretKey);
	}

	public String getReviewReport(String contentId)
	throws QueueException, ReportException {

		// first retrieve the record from the database to get the externalId of
		// the content
		log.warn("Deprecated Methog getReviewReport(String contentId) called");
		return this.getReviewReportInstructor(contentId);
	}

	/**
	 * private methods
	 */
	private String encodeParam(String name, String value, String boundary) {
		return "--" + boundary + "\r\nContent-Disposition: form-data; name=\""
		+ name + "\"\r\n\r\n" + value + "\r\n";
	}


	/**
	 * This method was originally private, but is being made public for the 
	 * moment so we can run integration tests. TODO Revisit this decision.
	 * 
	 * @param siteId
	 * @throws SubmissionException
	 * @throws TransientSubmissionException
	 */
	@SuppressWarnings("unchecked")
	public void createClass(String siteId) throws SubmissionException, TransientSubmissionException {
		log.debug("Creating class for site: " + siteId);

		String cpw = defaultClassPassword;
		String ctl = siteId;
		String diagnostic = "0";
		String encrypt = "0";	
		String fcmd = "2";
		String fid = "2";
		String uem = defaultInstructorEmail;
		String ufn = defaultInstructorFName;
		String uln = defaultInstructorLName;
		String utp = "2"; 					//user type 2 = instructor
		String upw = defaultInstructorPassword;
		String cid = siteId;
		String uid = defaultInstructorId;

		String gmtime = this.getGMTime();

		Document document = null;

		Map params = TurnitinAPIUtil.packMap(null,
				"uid", uid,
				"cid", cid,
				"aid", aid,
				"cpw", cpw,
				"ctl", ctl,
				"diagnostic", diagnostic,
				"encrypt", encrypt,
				"fcmd", fcmd,
				"fid", fid,
				"gmtime", gmtime,
				"said", said,
				"uem", uem,
				"ufn", ufn,
				"uln", uln,
				"utp", utp
		);

		if (useSourceParameter) {
			params = TurnitinAPIUtil.packMap(params, "src", "9");
		} else {
			params = TurnitinAPIUtil.packMap(params, "upw", upw);
		}

		document = TurnitinAPIUtil.callTurnitinReturnDocument(apiURL, params, secretKey, proxy);

		Element root = document.getDocumentElement();
		String rcode = ((CharacterData) (root.getElementsByTagName("rcode").item(0).getFirstChild())).getData().trim();

		if (((CharacterData) (root.getElementsByTagName("rcode").item(0).getFirstChild())).getData().trim().compareTo("20") == 0 || 
				((CharacterData) (root.getElementsByTagName("rcode").item(0).getFirstChild())).getData().trim().compareTo("21") == 0 ) {
			log.debug("Create Class successful");						
		} else {
			if ("218".equals(rcode) || "9999".equals(rcode)) {
				throw new TransientSubmissionException("Create Class not successful. Message: " + ((CharacterData) (root.getElementsByTagName("rmessage").item(0).getFirstChild())).getData().trim() + ". Code: " + ((CharacterData) (root.getElementsByTagName("rcode").item(0).getFirstChild())).getData().trim());
			} else {
				throw new SubmissionException("Create Class not successful. Message: " + ((CharacterData) (root.getElementsByTagName("rmessage").item(0).getFirstChild())).getData().trim() + ". Code: " + ((CharacterData) (root.getElementsByTagName("rcode").item(0).getFirstChild())).getData().trim());
			}
		}
	}

	/**
	 * This returns the String that will be used as the Assignment Title
	 * in Turn It In.
	 * 
	 * The current implementation here has a few interesting caveats so that
	 * it will work with both, the existing Assignments 1 integration, and
	 * the new Assignments 2 integration under development.
	 * 
	 * We will check and see if the taskId starts with /assignment/. If it
	 * does we will look up the Assignment Entity on the legacy Entity bus.
	 * (not the entitybroker).  This needs some general work to be made 
	 * generally modular ( and useful for more than just Assignments 1 and 2
	 * ). We will need to look at some more concrete use cases and then
	 * factor it accordingly in the future when the next scenerio is 
	 * required.
	 * 
	 * Another oddity is that to get rid of our hard dependency on Assignments 1
	 * we are invoking the getTitle method by hand. We probably need a 
	 * mechanism to register a title handler or something as part of the 
	 * setup process for new services that want to be reviewable.
	 * 
	 * @param taskId
	 * @return
	 */
	private String getAssignmentTitle(String taskId){
		String togo = taskId;
		if (taskId.startsWith("/assignment/")) {
			try {
				Reference ref = entityManager.newReference(taskId);
				log.debug("got ref " + ref + " of type: " + ref.getType());
				EntityProducer ep = ref.getEntityProducer();

				Entity ent = ep.getEntity(ref);
				log.debug("got entity " + ent);
				String title = 
					ent.getClass().getMethod("getTitle").invoke(ent).toString();
				log.debug("Got reflected assignemment title from entity " + title);
				togo = URLDecoder.decode(title,"UTF-8");

			} catch (Exception e) {
				e.printStackTrace();
			}
		}

		return togo;

	}

	/**
	 * @param siteId
	 * @param taskId
	 * @throws SubmissionException
	 * @throws TransientSubmissionException
	 */
	public void createAssignment(String siteId, String taskId) throws SubmissionException, TransientSubmissionException {
		createAssignment(siteId, taskId, null);
	}

	@SuppressWarnings("unchecked")
	public Map getAssignment(String siteId, String taskId) throws SubmissionException, TransientSubmissionException {
		String taskTitle = getAssignmentTitle(taskId);
		String diagnostic = "0"; //0 = off; 1 = on

		Map params = TurnitinAPIUtil.packMap(null,
				"aid", aid, "assign", taskTitle, "assignid", taskId,
				"cid", siteId, "uid", this.getProvisionerUserID(), "ctl", siteId,
				"diagnostic", "0", "encrypt", "0",
				"fcmd", "7", "fid", "4", "gmtime", getGMTime(),
				"said", said,
				"uem", this.getProvisionerEmail(), "ufn", this.getProvisionerFName(), "uln", this.getProvisionerLName(),
				"utp", "2" ); // "upw", defaultInstructorPassword,

		return TurnitinAPIUtil.callTurnitinReturnMap(apiURL, params, secretKey, proxy);
	}

	private String getProvisionerEmail() {
		if (this.useSourceParameter) {
			return userDirectoryService.getCurrentUser().getEmail();
		}
		else {
			return defaultInstructorEmail;
		}
	}

	private String getProvisionerFName() {
		if (this.useSourceParameter) {
			return userDirectoryService.getCurrentUser().getFirstName();
		}
		else {
			return defaultInstructorFName;
		}
	}

	private String getProvisionerLName() {
		if (this.useSourceParameter) {
			return userDirectoryService.getCurrentUser().getLastName();
		}
		else {
			return defaultInstructorLName;
		}
	}

	private String getProvisionerUserID() {
		if (this.useSourceParameter) {
			return userDirectoryService.getCurrentUser().getId();
		}
		else {
			return defaultInstructorId;
		}
	}

	/**
	 * Creates or Updates an Assignment
	 * 
	 * @param siteId
	 * @param taskId
	 * @param extraAsnnOpts
	 * @throws SubmissionException
	 * @throws TransientSubmissionException
	 */
	@SuppressWarnings("unchecked")
	public void createAssignment(String siteId, String taskId, Map extraAsnnOpts) throws SubmissionException, TransientSubmissionException {

		//get the assignment reference
		String taskTitle = getAssignmentTitle(taskId);
		log.debug("Creating assignment for site: " + siteId + ", task: " + taskId +" tasktitle: " + taskTitle);

		String diagnostic = "0"; //0 = off; 1 = on

		SimpleDateFormat dform = ((SimpleDateFormat) DateFormat.getDateInstance());
		dform.applyPattern("yyyyMMdd");
		Calendar cal = Calendar.getInstance();
		//set this to yesterday so we avoid timezine probelms etc
		cal.add(Calendar.DAY_OF_MONTH, -1);
		String dtstart = dform.format(cal.getTime());


		//set the due dates for the assignments to be in 5 month's time
		//turnitin automatically sets each class end date to 6 months after it is created
		//the assignment end date must be on or before the class end date

		//TODO use the 'secret' function to change this to longer
		cal.add(Calendar.MONTH, 5);
		String dtdue = dform.format(cal.getTime());
		if (extraAsnnOpts != null && extraAsnnOpts.containsKey("dtdue")) {
			dtdue = extraAsnnOpts.get("dtdue").toString();
			extraAsnnOpts.remove("dtdue");
		}

		String encrypt = "0";					//encryption flag
		String fcmd = "2";						//new assignment

		// If this assignment already exists, we should use fcmd 3 to update it.
		Map tiiresult = this.getAssignment(siteId, taskId);
		if (tiiresult.get("rcode") != null && tiiresult.get("rcode").equals("85")) {
			fcmd = "3";
		}

		String fid = "4";						//function id
		String uem = this.getProvisionerEmail(); // defaultInstructorEmail;
		String ufn = this.getProvisionerFName(); // defaultInstructorFName;
		String uln = this.getProvisionerLName(); // defaultInstructorLName;
		String utp = "2"; 					//user type 2 = instructor
		String upw = defaultInstructorPassword;
		String s_view_report = "1";
		if (extraAsnnOpts != null && extraAsnnOpts.containsKey("s_view_report")) {
			s_view_report = extraAsnnOpts.get("s_view_report").toString();
			extraAsnnOpts.remove("s_view_report");
		}

		String cid = siteId;
		String uid = defaultInstructorId;
		String assignid = taskId;
		String assign = taskTitle;
		String ctl = siteId;

		String gmtime = getGMTime();
		String assignEnc = assign;
		try {
			if (assign.contains("&")) {
				//log.debug("replacing & in assingment title");
				assign = assign.replace('&', 'n');

			}
			assignEnc = assign;
			log.debug("Assign title is " + assignEnc);

		}
		catch (Exception e) {
			e.printStackTrace();
		}

		Document document = null;

		Map params = TurnitinAPIUtil.packMap(null, 
				"aid", aid, 
				"assign", assignEnc, 
				"assignid", assignid, 
				"cid", cid,
				"uid", uid,
				"ctl", ctl,	
				"diagnostic", diagnostic,
				"dtdue", dtdue,
				"dtstart", dtstart,
				"encrypt", encrypt,
				"fcmd", fcmd,
				"fid", fid,
				"gmtime", gmtime,
				"s_view_report", s_view_report, 
				"said", said, 
				"uem", uem, 
				"ufn", ufn,
				"uln", uln,
				"utp", utp
		);

		if (this.useSourceParameter) {
			params = TurnitinAPIUtil.packMap(params, "src", "9");
		} else {
			params = TurnitinAPIUtil.packMap(params, "upw", upw);
		}

		if (extraAsnnOpts != null) {
			for (Object key: extraAsnnOpts.keySet()) {
				if (extraAsnnOpts.get(key) == null) {
					continue;
				}
				params = TurnitinAPIUtil.packMap(params, key.toString(), 
						extraAsnnOpts.get(key).toString());
			}
		}

		document = TurnitinAPIUtil.callTurnitinReturnDocument(apiURL, params, secretKey, proxy);

		Element root = document.getDocumentElement();
		int rcode = new Integer(((CharacterData) (root.getElementsByTagName("rcode").item(0).getFirstChild())).getData().trim()).intValue();
		if ((rcode > 0 && rcode < 100) || rcode == 419) {
			log.debug("Create Assignment successful");	
			log.debug("tii returned " + ((CharacterData) (root.getElementsByTagName("rmessage").item(0).getFirstChild())).getData().trim() + ". Code: " + rcode);
		} else {
			log.debug("Assignment creation failed with message: " + ((CharacterData) (root.getElementsByTagName("rmessage").item(0).getFirstChild())).getData().trim() + ". Code: " + rcode);
			//log.debug(root);
			throw new TransientSubmissionException("Create Assignment not successful. Message: " + ((CharacterData) (root.getElementsByTagName("rmessage").item(0).getFirstChild())).getData().trim() + ". Code: " + rcode);
		}
	}

	private String getTEM(String cid) {
		if (useSourceParameter) {
			return cid + "_" + this.aid + "@tiisakai.com";
		} else {
			return defaultInstructorEmail;
		}
	}

	/**
	 * Currently public for integration tests. TODO Revisit visibility of
	 * method.
	 * 
	 * @param userId
	 * @param uem
	 * @param siteId
	 * @throws SubmissionException
	 */
	public void enrollInClass(String userId, String uem, String siteId) throws SubmissionException, TransientSubmissionException {

		String uid = userId;
		String cid = siteId;

		String ctl = siteId; 			//class title
		String fid = "3";
		String fcmd = "2";
		String encrypt = "0";
		String diagnostic = "0";
		String tem = getTEM(cid);

		User user;
		try {
			user = userDirectoryService.getUser(userId);
		} catch (Exception t) {
			throw new SubmissionException ("Cannot get user information", t);
		}

		log.debug("Enrolling user " + user.getEid() + "(" + userId + ")  in class " + siteId);

		/* not using this as we may be getting email from profile
		String uem = user.getEmail();
		if (uem == null) {
			throw new SubmissionException ("User has no email address");
		}
		 */

		String ufn = getUserFirstName(user);
		if (ufn == null) {
			throw new SubmissionException ("User has no first name");
		}

		String uln = user.getLastName();
		if (uln == null) {
			throw new SubmissionException ("User has no last name");
		}

		String utp = "1";

		String gmtime = this.getGMTime();

		Map params = new HashMap();
		try {
			params = TurnitinAPIUtil.packMap(null, 
					"fid", fid,
					"fcmd", fcmd,
					"cid", cid,
					"tem", tem,
					"ctl", ctl,
					"encrypt", encrypt,
					"aid", aid,
					"said", said,
					"diagnostic", diagnostic,
					"dis", Integer.valueOf(sendNotifications).toString(),
					"uem", uem,
					"ufn", ufn,
					"uln", uln,
					"utp", utp,
					"gmtime", URLEncoder.encode(gmtime, "UTF-8"),
					"uid", uid
			);
		} catch (java.io.UnsupportedEncodingException e) {
			throw new SubmissionException("Unable to encode gmttime as UTF-8: " + gmtime, e);
		}

		if (useSourceParameter) {
			params = TurnitinAPIUtil.packMap(params, "src", "9");
		}

		Document document = TurnitinAPIUtil.callTurnitinReturnDocument(apiURL, params, secretKey, proxy);

		Element root = document.getDocumentElement();

		String rMessage = ((CharacterData) (root.getElementsByTagName("rmessage").item(0).getFirstChild())).getData();
		String rCode = ((CharacterData) (root.getElementsByTagName("rcode").item(0).getFirstChild())).getData();
		log.debug("Results from enrollInClass with user + " + userId + " and class title: " + ctl + ".\n" +
				"rCode: " + rCode + " rMessage: " + rMessage);
	}

	/*
	 * Get the next item that needs to be submitted
	 *
	 */

	private ContentReviewItem getNextItemInSubmissionQueue() {


		ContentReviewItem searchItem = new ContentReviewItem();
		searchItem.setContentId(null);
		searchItem.setStatus(ContentReviewItem.NOT_SUBMITTED_CODE);

		List notSubmittedItems = dao.findByExample(searchItem);
		for (int i =0; i < notSubmittedItems.size(); i++) {
			ContentReviewItem item = (ContentReviewItem)notSubmittedItems.get(0);
			//can we get a lock

			if (dao.obtainLock("item." + Long.valueOf(item.getId()).toString(), serverConfigurationService.getServerId(), LOCK_PERIOD))
				return  item;

		}

		searchItem.setStatus(ContentReviewItem.SUBMISSION_ERROR_RETRY_CODE);
		notSubmittedItems = dao.findByExample(searchItem);

		//we need the next one whose retry time has not been reached
		for  (int i =0; i < notSubmittedItems.size(); i++ ) {
			ContentReviewItem item = (ContentReviewItem)notSubmittedItems.get(i);
			if (hasReachedRetryTime(item) && dao.obtainLock("item." + Long.valueOf(item.getId()).toString(), serverConfigurationService.getServerId(), LOCK_PERIOD))
				return item;

		}

		return null;
	}

	private boolean hasReachedRetryTime(ContentReviewItem item) {

		// has the item reached its next retry time?
		if (item.getNextRetryTime() == null)
			item.setNextRetryTime(new Date());

		if (item.getNextRetryTime().after(new Date())) {
			//we haven't reached the next retry time
			log.info("next retry time not yet reached for item: " + item.getId());
			dao.update(item);
			return false;
		}

		return true;

	}

	private void releaseLock(ContentReviewItem currentItem) {
		dao.releaseLock("item." + currentItem.getId().toString(), serverConfigurationService.getServerId());
	}

	public void processQueue() {
		log.debug("Processing submission queue");

		for (ContentReviewItem currentItem = getNextItemInSubmissionQueue(); currentItem != null; currentItem = getNextItemInSubmissionQueue()) {

			log.debug("Attempting to submit content: " + currentItem.getContentId() + " for user: " + currentItem.getUserId() + " and site: " + currentItem.getSiteId());

			if (currentItem.getRetryCount() == null ) {
				currentItem.setRetryCount(Long.valueOf(0));
				currentItem.setNextRetryTime(this.getNextRetryTime(0));
				dao.update(currentItem);
			} else if (currentItem.getRetryCount().intValue() > maxRetry) {
				currentItem.setStatus(ContentReviewItem.SUBMISSION_ERROR_RETRY_EXCEEDED);
				dao.update(currentItem);
				continue;
			} else {
				long l = currentItem.getRetryCount().longValue();
				l++;
				currentItem.setRetryCount(Long.valueOf(l));
				currentItem.setNextRetryTime(this.getNextRetryTime(Long.valueOf(l)));
				dao.update(currentItem);
			}

			User user;

			try {
				user = userDirectoryService.getUser(currentItem.getUserId());
			} catch (UserNotDefinedException e1) {
				log.debug("Submission attempt unsuccessful - User not found: " + e1.getMessage());
				currentItem.setStatus(ContentReviewItem.SUBMISSION_ERROR_NO_RETRY_CODE);
				dao.update(currentItem);
				releaseLock(currentItem);
				continue;
			}


			String uem = getEmail(user);
			if (uem == null ){
				log.debug("User: " + user.getEid() + " has no valid email");
				currentItem.setStatus(ContentReviewItem.SUBMISSION_ERROR_USER_DETAILS_CODE);
				currentItem.setLastError("no valid email");
				dao.update(currentItem);
				releaseLock(currentItem);
				continue;
			}

			String ufn = getUserFirstName(user);
			if (ufn == null || ufn.equals("")) {
				log.debug("Submission attempt unsuccessful - User has no first name");
				currentItem.setStatus(ContentReviewItem.SUBMISSION_ERROR_USER_DETAILS_CODE);
				currentItem.setLastError("has no first name");
				dao.update(currentItem);
				releaseLock(currentItem);
				continue;
			}

			String uln = user.getLastName().trim();
			if (uln == null || uln.equals("")) {
				log.debug("Submission attempt unsuccessful - User has no last name");
				currentItem.setStatus(ContentReviewItem.SUBMISSION_ERROR_USER_DETAILS_CODE);
				currentItem.setLastError("has no last name");
				dao.update(currentItem);
				releaseLock(currentItem);
				continue;
			}

			if (!useSourceParameter) {
				try {				
					createClass(currentItem.getSiteId());
				} catch (SubmissionException t) {
					log.debug ("Submission attempt unsuccessful: Could not create class", t);
					currentItem.setLastError("Class creation error: " + t.getMessage());
					currentItem.setStatus(ContentReviewItem.SUBMISSION_ERROR_RETRY_CODE);
					dao.update(currentItem);
					releaseLock(currentItem);
					continue;
				} catch (TransientSubmissionException tse) {
					currentItem.setLastError("Class creation error: " + tse.getMessage());
					currentItem.setStatus(ContentReviewItem.SUBMISSION_ERROR_RETRY_CODE);
					dao.update(currentItem);
					releaseLock(currentItem);
					continue;
				}
			}

			try {
				enrollInClass(currentItem.getUserId(), uem, currentItem.getSiteId());
			} catch (Exception t) {
				log.debug ("Submission attempt unsuccessful: Could not enroll user in class", t);

				if (t.getClass() == IOException.class) {
					currentItem.setLastError("Enrolment error: " + t.getMessage() );
					currentItem.setStatus(ContentReviewItem.SUBMISSION_ERROR_RETRY_CODE);
				} else {
					currentItem.setLastError("Enrolment error: " + t.getMessage());
					currentItem.setStatus(ContentReviewItem.SUBMISSION_ERROR_RETRY_CODE);
				}
				dao.update(currentItem);
				releaseLock(currentItem);
				continue;
			}

			if (!useSourceParameter) {
				try {
					Map tiiresult = this.getAssignment(currentItem.getSiteId(), currentItem.getTaskId());
					if (tiiresult.get("rcode") != null && !tiiresult.get("rcode").equals("85")) {
						createAssignment(currentItem.getSiteId(), currentItem.getTaskId());
					}
				} catch (SubmissionException se) {
					currentItem.setLastError("Assign creation error: " + se.getMessage());
					currentItem.setStatus(ContentReviewItem.SUBMISSION_ERROR_NO_RETRY_CODE);
					dao.update(currentItem);
					releaseLock(currentItem);
					continue;
				} catch (TransientSubmissionException tse) {
					currentItem.setLastError("Assign creation error: " + tse.getMessage());
					currentItem.setStatus(ContentReviewItem.SUBMISSION_ERROR_RETRY_CODE);
					dao.update(currentItem);
					releaseLock(currentItem);
					continue;

				} 
			}

			//get all the info for the api call
			//we do this before connecting so that if there is a problem we can jump out - saves time
			//these errors should probably be caught when a student is enrolled in a class
			//but we check again here to be sure

			String diagnostic = "0";
			String encrypt = "0";
			String fcmd = "2";
			String fid = "5";

			//to get the name of the initial submited file we need the title
			ContentResource resource = null;
			ResourceProperties resourceProperties = null;
			String fileName = null;
			try {
				try {
					resource = contentHostingService.getResource(currentItem.getContentId());

				} catch (IdUnusedException e4) {
					//ToDo we should probably remove these from the Queue
					log.warn("IdUnusedException: no resource with id " + currentItem.getContentId());
					dao.delete(currentItem);
					continue;
				}
				resourceProperties = resource.getProperties();
				fileName = resourceProperties.getProperty(resourceProperties.getNamePropDisplayName());
				log.debug("origional filename is: " + fileName);
				if (fileName == null) {
					//use the id 
					fileName  = currentItem.getContentId();
				} else if (fileName.length() > 199) {
					fileName = fileName.substring(0, 199);
				}
				log.debug("fileName is :" + fileName);
				try {
					fileName = URLDecoder.decode(fileName, "UTF-8");
					//in rare cases it seems filenames can be double encoded
					while (fileName.indexOf("%20")> 0 || fileName.contains("%2520") ) {
						try {
							fileName = URLDecoder.decode(fileName, "UTF-8");
						}
						catch (IllegalArgumentException eae) {
							log.warn("Unable to decode fileName: " + fileName);
							currentItem.setStatus(ContentReviewItem.SUBMISSION_ERROR_NO_RETRY_CODE);
							currentItem.setLastError("FileName decode exception: " + fileName);
							dao.update(currentItem);
							releaseLock(currentItem);
							throw new SubmissionException("Can't decode fileName!");
						}

					}
				} 
				catch (IllegalArgumentException eae) {
					log.warn("Unable to decode fileName: " + fileName);
				}  catch (SubmissionException se) {
					log.debug("got a submission exception from decoding");
					continue;
				}

				fileName = fileName.replace(' ', '_');
				log.debug("fileName is :" + fileName);
			}
			catch (PermissionException e2) {
				log.debug("Submission failed due to permission error: " + e2.getMessage());
				currentItem.setStatus(ContentReviewItem.SUBMISSION_ERROR_NO_RETRY_CODE);
				currentItem.setLastError("Permission exception: " + e2.getMessage());
				dao.update(currentItem);
				releaseLock(currentItem);
				continue;
			} 
			catch (TypeException e) {
				log.debug("Submission failed due to content Type error: " + e.getMessage());
				currentItem.setStatus(ContentReviewItem.SUBMISSION_ERROR_NO_RETRY_CODE);
				currentItem.setLastError("Type Exception: " + e.getMessage());
				dao.update(currentItem);
				releaseLock(currentItem);
				continue;
			} catch (UnsupportedEncodingException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}

			String userEid = currentItem.getUserId();
			try {
				userEid = userDirectoryService.getUserEid(currentItem.getUserId());
			}
			catch (UserNotDefinedException unde) {
				//nothing realy to do?
			}

			String ptl =  userEid  + ":" + fileName;
			String ptype = "2";

			String uid = currentItem.getUserId();
			String cid = currentItem.getSiteId();
			String assignid = currentItem.getTaskId();

			// TODO ONC-1292 How to get this, and is it still required with src=9?
			String tem = getTEM(cid);

			String utp = "1";

			log.warn("Using Emails: tem: " + tem + " uem: " + uem);

			String assign = getAssignmentTitle(currentItem.getTaskId());;
			String ctl = currentItem.getSiteId();

			String gmtime = this.getGMTime();

			Map params = TurnitinAPIUtil.packMap( null,
					"assignid", assignid,
					"uid", uid,
					"cid", cid,
					"aid", aid,
					"assign", assign,
					"ctl", ctl,
					"diagnostic", diagnostic,
					"dis", Integer.valueOf(sendNotifications).toString(),
					"encrypt", encrypt,
					"fcmd", fcmd,
					"fid", fid,
					"gmtime", gmtime,
					"ptype", ptype,
					"ptl", ptl,
					"said", said,
					"tem", tem,
					"uem", uem,
					"ufn", ufn,
					"uln", uln,
					"utp", utp,
					"resource_obj", resource
			);

			if (useSourceParameter) {
				params = TurnitinAPIUtil.packMap(params, "src", "9");
				//outStream.write(encodeParam("src", "9", boundary).getBytes());
			}

			Document document = null;
			try {
				document = TurnitinAPIUtil.callTurnitinReturnDocument(apiURL, params, secretKey, proxy, true);
			}
			catch (TransientSubmissionException e) {
				currentItem.setStatus(ContentReviewItem.SUBMISSION_ERROR_RETRY_CODE);
				currentItem.setLastError("Error Submitting Assignment for Submission: " + e.getMessage() + ". Assume unsuccessful");
				dao.update(currentItem);
				releaseLock(currentItem);
				continue;
			}
			catch (SubmissionException e) {
				currentItem.setStatus(ContentReviewItem.SUBMISSION_ERROR_RETRY_CODE);
				currentItem.setLastError("Error Submitting Assignment for Submission: " + e.getMessage() + ". Assume unsuccessful");
				dao.update(currentItem);
				releaseLock(currentItem);
				continue;
			}

			Element root = document.getDocumentElement();

			String rMessage = ((CharacterData) (root.getElementsByTagName("rmessage").item(0).getFirstChild())).getData();
			String rCode = ((CharacterData) (root.getElementsByTagName("rcode").item(0).getFirstChild())).getData();

			if (rCode == null)
				rCode = "";
			else
				rCode = rCode.trim();

			if (rMessage == null)
				rMessage = rCode;
			else 
				rMessage = rMessage.trim();

			if (rCode.compareTo("51") == 0) {
				String externalId = ((CharacterData) (root.getElementsByTagName("objectID").item(0).getFirstChild())).getData().trim();
				if (externalId != null && externalId.length() >0 ) {
					log.debug("Submission successful");
					currentItem.setExternalId(externalId);
					currentItem.setStatus(ContentReviewItem.SUBMITTED_AWAITING_REPORT_CODE);
					currentItem.setRetryCount(Long.valueOf(0));
					currentItem.setLastError(null);
					currentItem.setDateSubmitted(new Date());
					dao.update(currentItem);
				} else {
					log.warn("invalid external id");
					currentItem.setLastError("Submission error: no external id received");
					currentItem.setStatus(ContentReviewItem.SUBMISSION_ERROR_RETRY_CODE);
					dao.update(currentItem);
				}
			} else {
				log.debug("Submission not successful: " + ((CharacterData) (root.getElementsByTagName("rmessage").item(0).getFirstChild())).getData().trim());

				if (rMessage.equals("User password does not match user email") 
						|| "1001".equals(rCode) || "".equals(rMessage) || "413".equals(rCode) || "1025".equals(rCode)) {
					currentItem.setStatus(ContentReviewItem.SUBMISSION_ERROR_RETRY_CODE);
				} else if (rCode.equals("423")) {
					currentItem.setStatus(ContentReviewItem.SUBMISSION_ERROR_USER_DETAILS_CODE);

				} else if (rCode.equals("301")) {
					//this took a long time
					currentItem.setStatus(ContentReviewItem.SUBMISSION_ERROR_RETRY_CODE);
					Calendar cal = Calendar.getInstance();
					cal.set(Calendar.HOUR_OF_DAY, 22);
					currentItem.setNextRetryTime(cal.getTime());

				}else {
					currentItem.setStatus(ContentReviewItem.SUBMISSION_ERROR_NO_RETRY_CODE);
				}
				currentItem.setLastError("Submission Error: " + rMessage + "(" + rCode + ")");
				dao.update(currentItem);

			}
			//release the lock so the reports job can handle it
			releaseLock(currentItem);
			getNextItemInSubmissionQueue();
		}


	}

	private String getGMTime() {
		// calculate function2 data
		SimpleDateFormat dform = ((SimpleDateFormat) DateFormat
				.getDateInstance());
		dform.applyPattern("yyyyMMddHH");
		dform.setTimeZone(TimeZone.getTimeZone("GMT"));
		Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("GMT"));

		String gmtime = dform.format(cal.getTime());
		gmtime += Integer.toString(((int) Math.floor((double) cal
				.get(Calendar.MINUTE) / 10)));

		return gmtime;
	}

	public void checkForReports() {
		if (serverConfigurationService.getBoolean("turnitin.getReportsBulk", true))
			checkForReportsBulk();
		else 
			checkForReportsIndividual();

	}

	/*
	 * Fetch reports on a class by class basis
	 */
	@SuppressWarnings({ "deprecation", "unchecked" })
	public void checkForReportsBulk() {

		log.debug("Checking for updated reports from Turnitin in bulk mode");

		// get the list of all items that are waiting for reports
		List<ContentReviewItem> awaitingReport = dao.findByProperties(ContentReviewItem.class,
				new String[] { "status" },
				new Object[] { ContentReviewItem.SUBMITTED_AWAITING_REPORT_CODE});

		awaitingReport.addAll(dao.findByProperties(ContentReviewItem.class,
				new String[] { "status" },
				new Object[] { ContentReviewItem.REPORT_ERROR_RETRY_CODE}));

		Iterator<ContentReviewItem> listIterator = awaitingReport.iterator();
		HashMap<String, Integer> reportTable = new HashMap<String, Integer>();

		log.debug("There are " + awaitingReport.size() + " submissions awaiting reports");

		ContentReviewItem currentItem;
		while (listIterator.hasNext()) {
			currentItem = (ContentReviewItem) listIterator.next();

			// has the item reached its next retry time?
			if (currentItem.getNextRetryTime() == null)
				currentItem.setNextRetryTime(new Date());

			if (currentItem.getNextRetryTime().after(new Date())) {
				//we haven't reached the next retry time
				log.info("next retry time not yet reached for item: " + currentItem.getId());
				dao.update(currentItem);
				continue;
			}

			if (currentItem.getRetryCount() == null ) {
				currentItem.setRetryCount(Long.valueOf(0));
				currentItem.setNextRetryTime(this.getNextRetryTime(0));
			} else if (currentItem.getRetryCount().intValue() > maxRetry) {
				currentItem.setStatus(ContentReviewItem.SUBMISSION_ERROR_RETRY_EXCEEDED);
				dao.update(currentItem);
				continue;
			} else {
				long l = currentItem.getRetryCount().longValue();
				l++;
				currentItem.setRetryCount(Long.valueOf(l));
				currentItem.setNextRetryTime(this.getNextRetryTime(Long.valueOf(l)));
				dao.update(currentItem);
			}

			if (currentItem.getExternalId() == null || currentItem.getExternalId().equals("")) {
				currentItem.setStatus(Long.valueOf(4));
				dao.update(currentItem);
				continue;
			}

			if (!reportTable.containsKey(currentItem.getExternalId())) {
				// get the list from turnitin and see if the review is available

				log.debug("Attempting to update hashtable with reports for site " + currentItem.getSiteId());

				String diagnostic = "0";
				String encrypt = "0";
				String fcmd = "2";
				String fid = "10";

				try {
					User user = userDirectoryService.getUser(currentItem.getUserId());
				} catch (Exception e) {
					log.error("Unable to look up user: " + currentItem.getUserId() + " for contentItem: " + currentItem.getId(), e);
				}

				String cid = currentItem.getSiteId();
				String tem = getTEM(cid);

				String uem = getTEM(cid);
				//String uem = defaultInstructorEmail;
				//String ufn = defaultInstructorFName;
				//String uln = defaultInstructorLName;
				String ufn = "Sakai";
				String uln = "Instructor";
				String utp = "2";

				//String uid = defaultInstructorId;

				String assignid = currentItem.getTaskId();

				String assign = currentItem.getTaskId();
				String ctl = currentItem.getSiteId();

				String gmtime = this.getGMTime();

				Map params = new HashMap();
				try {
					params = TurnitinAPIUtil.packMap(null, 
							"fid", fid,
							"fcmd", fcmd,
							//"uid", uid,
							"tem", tem,
							"assign", assign,
							"assignid", assignid,
							"cid", cid,
							"ctl", ctl,
							"encrypt", encrypt,
							"aid", aid,
							"said", said,
							"diagnostic", diagnostic,
							"uem", URLEncoder.encode(uem, "UTF-8"),
							"ufn", ufn,
							"uln", uln,
							"utp", utp,
							"gmtime", URLEncoder.encode(gmtime, "UTF-8")
					);

				}
				catch (java.io.UnsupportedEncodingException e) {
					log.debug("Unable to encode a URL param as UTF-8: " + e.toString());
					currentItem.setStatus(ContentReviewItem.REPORT_ERROR_RETRY_CODE);
					currentItem.setLastError(e.getMessage());
					dao.update(currentItem);
					break;						
				}

				if (useSourceParameter) {
					params = TurnitinAPIUtil.packMap(params, "src", "9");
				}

				Document document = null;

				try {
					document = TurnitinAPIUtil.callTurnitinReturnDocument(apiURL, params, secretKey, proxy);
				}
				catch (TransientSubmissionException e) {
					log.debug("Update failed due to TransientSubmissionException error: " + e.toString());
					currentItem.setStatus(ContentReviewItem.REPORT_ERROR_RETRY_CODE);
					currentItem.setLastError(e.getMessage());
					dao.update(currentItem);
					break;
				}
				catch (SubmissionException e) {
					log.debug("Update failed due to SubmissionException error: " + e.toString());
					currentItem.setStatus(ContentReviewItem.REPORT_ERROR_RETRY_CODE);
					currentItem.setLastError(e.getMessage());
					dao.update(currentItem);
					break;
				}

				Element root = document.getDocumentElement();
				if (((CharacterData) (root.getElementsByTagName("rcode").item(0).getFirstChild())).getData().trim().compareTo("72") == 0) {
					log.debug("Report list returned successfully");

					NodeList objects = root.getElementsByTagName("object");
					String objectId;
					String similarityScore;
					String overlap = "";
					log.debug(objects.getLength() + " objects in the returned list");
					for (int i=0; i<objects.getLength(); i++) {
						similarityScore = ((CharacterData) (((Element)(objects.item(i))).getElementsByTagName("similarityScore").item(0).getFirstChild())).getData().trim();
						objectId = ((CharacterData) (((Element)(objects.item(i))).getElementsByTagName("objectID").item(0).getFirstChild())).getData().trim();
						if (similarityScore.compareTo("-1") != 0) {
							overlap = ((CharacterData) (((Element)(objects.item(i))).getElementsByTagName("overlap").item(0).getFirstChild())).getData().trim();
							reportTable.put(objectId, Integer.valueOf(overlap));
						} else {
							reportTable.put(objectId, Integer.valueOf(-1));
						}

						log.debug("objectId: " + objectId + " similarity: " + similarityScore + " overlap: " + overlap);
					}
				} else {
					log.debug("Report list request not successful");
					log.debug(document.toString());

				}
			}

			int reportVal;
			// check if the report value is now there (there may have been a
			// failure to get the list above)
			if (reportTable.containsKey(currentItem.getExternalId())) {
				reportVal = ((Integer) (reportTable.get(currentItem
						.getExternalId()))).intValue();
				log.debug("reportVal for " + currentItem.getExternalId() + ": " + reportVal);
				if (reportVal != -1) {
					currentItem.setReviewScore(reportVal);
					currentItem
					.setStatus(ContentReviewItem.SUBMITTED_REPORT_AVAILABLE_CODE);
					currentItem.setDateReportReceived(new Date());
					dao.update(currentItem);
					log.debug("new report received: " + currentItem.getExternalId() + " -> " + currentItem.getReviewScore());
				}
			}
		}
	}

	public void checkForReportsIndividual() {
		log.debug("Checking for updated reports from Turnitin in individual mode");

		// get the list of all items that are waiting for reports
		List<ContentReviewItem> awaitingReport = dao.findByProperties(ContentReviewItem.class,
				new String[] { "status" },
				new Object[] { ContentReviewItem.SUBMITTED_AWAITING_REPORT_CODE});

		awaitingReport.addAll(dao.findByProperties(ContentReviewItem.class,
				new String[] { "status" },
				new Object[] { ContentReviewItem.REPORT_ERROR_RETRY_CODE}));

		Iterator<ContentReviewItem> listIterator = awaitingReport.iterator();
		HashMap<String, Integer> reportTable = new HashMap();

		log.debug("There are " + awaitingReport.size() + " submissions awaiting reports");

		ContentReviewItem currentItem;
		while (listIterator.hasNext()) {
			currentItem = (ContentReviewItem) listIterator.next();

			// has the item reached its next retry time?
			if (currentItem.getNextRetryTime() == null)
				currentItem.setNextRetryTime(new Date());

			if (currentItem.getNextRetryTime().after(new Date())) {
				//we haven't reached the next retry time
				log.info("next retry time not yet reached for item: " + currentItem.getId());
				dao.update(currentItem);
				continue;
			}

			if (currentItem.getRetryCount() == null ) {
				currentItem.setRetryCount(Long.valueOf(0));
				currentItem.setNextRetryTime(this.getNextRetryTime(0));
			} else if (currentItem.getRetryCount().intValue() > maxRetry) {
				currentItem.setStatus(ContentReviewItem.SUBMISSION_ERROR_RETRY_EXCEEDED);
				dao.update(currentItem);
				continue;
			} else {
				long l = currentItem.getRetryCount().longValue();
				l++;
				currentItem.setRetryCount(Long.valueOf(l));
				currentItem.setNextRetryTime(this.getNextRetryTime(Long.valueOf(l)));
				dao.update(currentItem);
			}

			if (currentItem.getExternalId() == null || currentItem.getExternalId().equals("")) {
				currentItem.setStatus(Long.valueOf(4));
				dao.update(currentItem);
				continue;
			}

			// get the list from turnitin and see if the review is available

			String diagnostic = "0";
			String encrypt = "0";
			String fcmd = "2";
			String fid = "10";

			String cid = currentItem.getSiteId();

			String tem = getTEM(cid);

			String uem = defaultInstructorEmail;
			//String ufn = defaultInstructorFName;
			//String uln = defaultInstructorLName;
			String ufn = "Sakai";
			String uln = "Instructor";
			String utp = "2";

			//String uid = defaultInstructorId;

			String assignid = currentItem.getTaskId();

			String assign = currentItem.getTaskId();
			String ctl = currentItem.getSiteId();

			String gmtime = this.getGMTime();
			String oid = currentItem.getExternalId();

			Map params = new HashMap();

			try {
				params = TurnitinAPIUtil.packMap(null,
						"fid", fid,
						"fcmd", fcmd,
						"tem", tem,
						"assign", assign,
						"assignid", assignid,
						"cid", cid,
						"ctl", ctl,
						"encrypt", encrypt,
						"aid", aid,
						"oid", oid,
						"said", said,
						"diagnostic", diagnostic,
						"uem", URLEncoder.encode(uem, "UTF-8"),
						"ufn", ufn,
						"uln", uln,
						"utp", utp,
						"gmtime", URLEncoder.encode(gmtime, "UTF-8")
				);
			}
			catch (java.io.UnsupportedEncodingException e) {
				log.debug("Unable to encode a URL param as UTF-8: " + e.toString());
				currentItem.setStatus(ContentReviewItem.REPORT_ERROR_RETRY_CODE);
				currentItem.setLastError(e.getMessage());
				dao.update(currentItem);
				break;						
			}

			if (useSourceParameter) {
				params = TurnitinAPIUtil.packMap(params, "src", "9");
			}

			Document document = null;
			try {
				document = TurnitinAPIUtil.callTurnitinReturnDocument(apiURL, params, secretKey, proxy);
			}
			catch (TransientSubmissionException e) {
				log.debug("Fid10fcmd2 failed due to TransientSubmissionException error: " + e.toString());
				currentItem.setStatus(ContentReviewItem.REPORT_ERROR_RETRY_CODE);
				currentItem.setLastError(e.getMessage());
				dao.update(currentItem);
				break;
			}
			catch (SubmissionException e) {
				log.debug("Fid10fcmd2 failed due to SubmissionException error: " + e.toString());
				currentItem.setStatus(ContentReviewItem.REPORT_ERROR_RETRY_CODE);
				currentItem.setLastError(e.getMessage());
				dao.update(currentItem);
				break;
			}

			Element root = document.getDocumentElement();
			if (((CharacterData) (root.getElementsByTagName("rcode").item(0).getFirstChild())).getData().trim().compareTo("72") == 0) {
				log.debug("Report list returned successfully");

				NodeList objects = root.getElementsByTagName("object");
				String objectId;
				String similarityScore;
				String overlap = "";
				log.debug(objects.getLength() + " objects in the returned list");
				for (int i=0; i<objects.getLength(); i++) {
					similarityScore = ((CharacterData) (((Element)(objects.item(i))).getElementsByTagName("similarityScore").item(0).getFirstChild())).getData().trim();
					objectId = ((CharacterData) (((Element)(objects.item(i))).getElementsByTagName("objectID").item(0).getFirstChild())).getData().trim();
					if (similarityScore.compareTo("-1") != 0) {
						overlap = ((CharacterData) (((Element)(objects.item(i))).getElementsByTagName("overlap").item(0).getFirstChild())).getData().trim();
						reportTable.put(objectId, Integer.valueOf(overlap));
					} else {
						reportTable.put(objectId, Integer.valueOf(-1));
					}

					log.debug("objectId: " + objectId + " similarity: " + similarityScore + " overlap: " + overlap);
				}
			} else {
				log.debug("Report list request not successful");
				log.debug(document.toString());
			}

			int reportVal;
			// check if the report value is now there (there may have been a
			// failure to get the list above)
			if (reportTable.containsKey(currentItem.getExternalId())) {
				reportVal = ((Integer) (reportTable.get(currentItem
						.getExternalId()))).intValue();
				log.debug("reportVal for " + currentItem.getExternalId() + ": " + reportVal);
				if (reportVal != -1) {
					currentItem.setReviewScore(reportVal);
					currentItem
					.setStatus(ContentReviewItem.SUBMITTED_REPORT_AVAILABLE_CODE);
					currentItem.setDateReportReceived(new Date());
					dao.update(currentItem);
					log.debug("new report received: " + currentItem.getExternalId() + " -> " + currentItem.getReviewScore());
				}
			}
		}

	}


	// returns null if no valid email exists
	private String getEmail(User user) {
		String uem = null;
		log.debug("Looking for email for " + user.getEid() + " with prefer system profile email set to " + this.preferSystemProfileEmail);
		if (!this.preferSystemProfileEmail) {
			uem = user.getEmail().trim();
			log.debug("got email of " + uem);
			if (uem == null || uem.equals("") || !isValidEmail(uem)) {
				//try the systemProfile
				SakaiPerson sp = sakaiPersonManager.getSakaiPerson(user.getId(), sakaiPersonManager.getSystemMutableType());
				if (sp != null ) {
					String uem2 = sp.getMail().trim();
					log.debug("Got system profile email of " + uem2);
					if (uem2 == null || uem2.equals("") || !isValidEmail(uem2)) {
						uem = null;
					} else {
						uem =  uem2;
					}
				} else {
					log.debug("this user has no systemMutable profile");
					uem = null;
				}
			}
		} else {
			//try sakaiperson first
			log.debug("try system profile email first");
			SakaiPerson sp = sakaiPersonManager.getSakaiPerson(user.getId(), sakaiPersonManager.getSystemMutableType());
			if (sp != null && sp.getMail()!=null && sp.getMail().length()>0 ) {
				String uem2 = sp.getMail().trim();
				if (uem2 == null || uem2.equals("") || !isValidEmail(uem2)) {
					uem = user.getEmail().trim();
					log.debug("Got system profile email of " + uem2);
					if (uem == null || uem.equals("") || !isValidEmail(uem))
						uem = user.getEmail().trim();
					if (uem == null || uem.equals("") || !isValidEmail(uem))
						uem = null;
				} else {
					uem =  uem2;
				}
			} else {
				uem = user.getEmail().trim();
				if (uem == null || uem.equals("") || !isValidEmail(uem))
					uem = null;
			}
		}

		return uem;
	}


	/**
	 * Is this a valid email the service will recognize
	 * @param email
	 * @return
	 */
	private boolean isValidEmail(String email) {

		// TODO: Use a generic Sakai utility class (when a suitable one exists)

		if (email == null || email.equals(""))
			return false;

		email = email.trim();
		//must contain @
		if (email.indexOf("@") == -1)
			return false;

		//an email can't contain spaces
		if (email.indexOf(" ") > 0)
			return false;

		//"^[_A-Za-z0-9-]+(\\.[_A-Za-z0-9-]+)*@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*$" 
		if (email.matches("^[_A-Za-z0-9-]+(\\.[_A-Za-z0-9-]+)*@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*$")) 
			return true;

		return false;
	}


	//Methods for updating all assignments that exist
	public void doAssignments() {
		log.info("About to update all turnitin assignments");
		String statement = "Select siteid,taskid from CONTENTREVIEW_ITEM group by siteid,taskid";
		Object[] fields = new Object[0];
		List objects = sqlService.dbRead(statement, fields, new SqlReader(){
			public Object readSqlResultRecord(ResultSet result)
			{
				try {
					ContentReviewItem c = new ContentReviewItem();
					c.setSiteId(result.getString(1));
					c.setTaskId(result.getString(2));
					return c;
				} catch (SQLException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
					return null;
				}

			}
		});

		for (int i = 0; i < objects.size(); i ++) {
			ContentReviewItem cri = (ContentReviewItem) objects.get(i);
			try {
				updateAssignment(cri.getSiteId(),cri.getTaskId());
			} catch (SubmissionException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}

		}
	}

	public void updateAssignment(String siteId, String taskId) throws SubmissionException {
		log.info("updateAssignment(" + siteId +" , " + taskId + ")");
		//get the assignment reference
		String taskTitle = getAssignmentTitle(taskId);
		log.debug("Creating assignment for site: " + siteId + ", task: " + taskId +" tasktitle: " + taskTitle);

		String diagnostic = "0"; //0 = off; 1 = on

		SimpleDateFormat dform = ((SimpleDateFormat) DateFormat.getDateInstance());
		dform.applyPattern("yyyyMMdd");
		Calendar cal = Calendar.getInstance();
		//set this to yesterday so we avoid timezpne problems etc
		cal.add(Calendar.DAY_OF_MONTH, -1);
		String dtstart = dform.format(cal.getTime());


		//set the due dates for the assignments to be in 5 month's time
		//turnitin automatically sets each class end date to 6 months after it is created
		//the assignment end date must be on or before the class end date

		//TODO use the 'secret' function to change this to longer
		cal.add(Calendar.MONTH, 5);
		String dtdue = dform.format(cal.getTime());

		String encrypt = "0";					//encryption flag
		String fcmd = "3";						//new assignment
		String fid = "4";						//function id
		String uem = defaultInstructorEmail;
		String ufn = defaultInstructorFName;
		String uln = defaultInstructorLName;
		String utp = "2"; 					//user type 2 = instructor
		String upw = defaultInstructorPassword;
		String s_view_report = "1";

		String cid = siteId;
		String uid = defaultInstructorId;
		String assignid = taskId;
		String assign = taskTitle;
		String ctl = siteId;

		String gmtime = getGMTime();
		String assignEnc = assign;
		try {
			if (assign.contains("&")) {
				//log.debug("replacing & in assingment title");
				assign = assign.replace('&', 'n');

			}
			assignEnc = assign;
			log.debug("Assign title is " + assignEnc);

		}
		catch (Exception e) {
			e.printStackTrace();
		}

		Map params = TurnitinAPIUtil.packMap(null, 
				"aid", aid,
				"assign", assignEnc,
				"assignid", assignid,
				"cid", cid,
				"uid", uid,
				"ctl", ctl,
				"diagnostic", diagnostic,
				"dtdue", dtdue,
				"dtstart", dtstart,
				"encrypt", encrypt,
				"fcmd", fcmd,
				"fid", fid,
				"gmtime", gmtime,
				"s_view_report", s_view_report,
				"said", said,
				"uem", uem,
				"ufn", ufn,
				"uln", uln,
				"upw", upw,
				"utp", utp
		);
		
		if (useSourceParameter) {
			params = TurnitinAPIUtil.packMap(params, "src", "9");
		}

		Document document = null;
		
		try {
			document = TurnitinAPIUtil.callTurnitinReturnDocument(apiURL, params, secretKey, proxy);
		}
		catch (TransientSubmissionException tse) {
			log.error("Error on API call in updateAssignment siteid: " + siteId + " taskid: " + taskId, tse);
		}
		catch (SubmissionException se) {
			log.error("Error on API call in updateAssignment siteid: " + siteId + " taskid: " + taskId, se);
		}

		Element root = document.getDocumentElement();
		int rcode = new Integer(((CharacterData) (root.getElementsByTagName("rcode").item(0).getFirstChild())).getData().trim()).intValue();
		if ((rcode > 0 && rcode < 100) || rcode == 419) {
			log.debug("Create Assignment successful");						
		} else {
			log.debug("Assignment creation failed with message: " + ((CharacterData) (root.getElementsByTagName("rmessage").item(0).getFirstChild())).getData().trim() + ". Code: " + rcode);
			throw new SubmissionException("Create Assignment not successful. Message: " + ((CharacterData) (root.getElementsByTagName("rmessage").item(0).getFirstChild())).getData().trim() + ". Code: " + rcode);
		}
	}

	/**
	 * find the next time this item should be tried
	 * @param retryCount
	 * @return
	 */
	private Date getNextRetryTime(long retryCount) {
		int offset =5;

		if (retryCount > 9 && retryCount < 20) {

			offset = 10;

		} else if (retryCount > 19 && retryCount < 30) {
			offset = 20;
		} else if (retryCount > 29 && retryCount < 40) {
			offset = 40;
		} else if (retryCount > 39 && retryCount < 50) {
			offset = 80;
		} else if (retryCount > 49 && retryCount < 60) {
			offset = 160;
		} else if (retryCount > 59) {
			offset = 220;
		}

		Calendar cal = Calendar.getInstance();
		cal.add(Calendar.MINUTE, offset);
		return cal.getTime();
	}

	public Map callTurnitinWDefaultsReturnMap(Map<String,Object> parameters) throws TransientSubmissionException, SubmissionException {
		return TurnitinAPIUtil.callTurnitinReturnMap(apiURL, parameters, secretKey, proxy);
	}

	public Map callTurnitinReturnMap(String apiURL, Map<String,Object> parameters, 
			String secretKey, Proxy proxy) throws TransientSubmissionException, SubmissionException {
		return TurnitinAPIUtil.callTurnitinReturnMap(apiURL, parameters, secretKey, proxy);
	}

	/**
	 * Gets a first name for a user or generates an initial from the eid
	 * @param user a sakai user
	 * @return the first name or at least an initial if possible, "X" if no fn can be made
	 */
	private String getUserFirstName(User user) {
		String ufn = user.getFirstName().trim();
		if (ufn == null || ufn.equals("")) {
			boolean genFN = (boolean) serverConfigurationService.getBoolean("turnitin.generate.first.name", true);
			if (genFN) {
				String eid = user.getEid();
				if (eid != null 
						&& eid.length() > 0) {
					ufn = eid.substring(0,1);        
				} else {
					ufn = "X";
				}
			}
		}
		return ufn;
	}

}
