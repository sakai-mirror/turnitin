package org.sakaiproject.contentreview.impl.turnitin;

import java.util.HashMap;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.sakaiproject.contentreview.exception.SubmissionException;
import org.sakaiproject.contentreview.exception.TransientSubmissionException;
import org.sakaiproject.util.Xml;
import org.w3c.dom.CharacterData;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * This contains a few functional utilities to help with logging in and out of
 * Turnitin using fids 17 and 18.
 * 
 * Starting out this was for use in creating new assignments because we make
 * several back to back calls reading and writing the assignments to Turnitin 
 * and if you don't have a session id you might not hit the same node in their
 * cloud and the assignment you just created may not have been synced yet. 
 * 
 * @author sgithens
 *
 */
public class TurnitinSessionFuncs {
	private static final Log log = LogFactory.getLog(TurnitinSessionFuncs.class);

    /**
     * Logs in to turnitin.  Scrapes the response XML and returns the session id
     * or throws an exception if it was unable to log in.
     * 
     * @param conn
     * @param params This function will take care of the fids/fcmds. Just make
     * sure this has the defaults you use for every TII call as well as the 
     * user info including utp (even though utp doesn't seem to make sense 
     * since this doesn't include a course id parameter.)
     * @return session-id for use in other turnitin calls.
     * @throws TransientSubmissionException
     * @throws SubmissionException 
     */
    public static String getTurnitinSession(TurnitinAccountConnection conn,
            @SuppressWarnings("rawtypes") Map params) throws 
            TransientSubmissionException, SubmissionException {
        Map finalParams = new HashMap();
        finalParams.putAll(params);
        finalParams.put("fid", "17");
        finalParams.put("fcmd", "2");
        
        Document doc = conn.callTurnitinReturnDocument(finalParams);
        
        Element root = doc.getDocumentElement();
        int rcode = new Integer(((CharacterData) 
                (root.getElementsByTagName("rcode").item(0).getFirstChild())).getData().trim()).intValue();
        String message = ((CharacterData) 
                (root.getElementsByTagName("rmessage").item(0).getFirstChild())).getData().trim();
        
        if (!(rcode > 0 && rcode < 100)) {
            throw new TransientSubmissionException("Error logging in to turnitin: " + message);
        }
        
        String sessionId = ((CharacterData) 
                (root.getElementsByTagName("sessionid").item(0).getFirstChild())).getData().trim();
        
        log.debug("Log in results. rcode: " + rcode + " message: " + message +
        		" sessionId: " + sessionId);
        
        return sessionId;
    }
    
    /**
     * Logs out of a turnitin session. The params map should contain the same 
     * information as in {@link TurnitinSessionFuncs.getTurnitinSession}
     * 
     * @param conn
     * @param sessionId
     * @param params
     * @throws TransientSubmissionException
     * @throws SubmissionException
     */
    public static void logoutTurnitinSession(TurnitinAccountConnection conn,
            String sessionId, Map params) throws TransientSubmissionException, SubmissionException {
        Map finalParams = new HashMap();
        finalParams.putAll(params);
        finalParams.put("fid", "18");
        finalParams.put("fcmd", "2");
        finalParams.put("session-id", sessionId);
        
        conn.callTurnitinReturnDocument(finalParams);
    }
}
