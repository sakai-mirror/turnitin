package org.sakaiproject.component.app.scheduler.jobs;

import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.Job;

import org.sakaiproject.contentreview.service.ContentReviewService;

public class ContentReviewReports implements Job {

	private ContentReviewService contentReviewService;
	public void setContentReviewService(ContentReviewService sd){
		contentReviewService = sd;
		

	}
	
	public void execute(JobExecutionContext arg0) throws JobExecutionException {
		// TODO Auto-generated method stub
		//contentReviewService.processQueue();
		contentReviewService.checkForReports();
	}

}
