/**
 * Copyright (c) 2000-2012 Liferay, Inc. All rights reserved.
 *
 * This library is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 2.1 of the License, or (at your option)
 * any later version.
 *
 * This library is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 */

package com.liferay.portlet.documentlibrary.service.impl;

import com.liferay.portal.kernel.dao.orm.QueryDefinition;
import com.liferay.portal.kernel.exception.PortalException;
import com.liferay.portal.kernel.exception.SystemException;
import com.liferay.portal.kernel.log.Log;
import com.liferay.portal.kernel.log.LogFactoryUtil;
import com.liferay.portal.kernel.util.ObjectValuePair;
import com.liferay.portal.kernel.util.OrderByComparator;
import com.liferay.portal.kernel.util.ParamUtil;
import com.liferay.portal.kernel.util.StringPool;
import com.liferay.portal.kernel.util.UnicodeProperties;
import com.liferay.portal.kernel.workflow.WorkflowConstants;
import com.liferay.portal.model.Group;
import com.liferay.portal.model.ResourceConstants;
import com.liferay.portal.model.User;
import com.liferay.portal.repository.liferayrepository.model.LiferayFolder;
import com.liferay.portal.service.ServiceContext;
import com.liferay.portlet.asset.util.AssetUtil;
import com.liferay.portlet.documentlibrary.DuplicateFileException;
import com.liferay.portlet.documentlibrary.DuplicateFolderNameException;
import com.liferay.portlet.documentlibrary.FolderNameException;
import com.liferay.portlet.documentlibrary.NoSuchDirectoryException;
import com.liferay.portlet.documentlibrary.NoSuchFileEntryException;
import com.liferay.portlet.documentlibrary.model.DLFileEntryTypeConstants;
import com.liferay.portlet.documentlibrary.model.DLFolder;
import com.liferay.portlet.documentlibrary.model.DLFolderConstants;
import com.liferay.portlet.documentlibrary.service.base.DLFolderLocalServiceBaseImpl;
import com.liferay.portlet.documentlibrary.store.DLStoreUtil;
import com.liferay.portlet.trash.util.TrashUtil;

import java.io.Serializable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * @author Brian Wing Shun Chan
 * @author Alexander Chow
 */
public class DLFolderLocalServiceImpl extends DLFolderLocalServiceBaseImpl {

	public DLFolder addFolder(
			long userId, long groupId, long repositoryId, boolean mountPoint,
			long parentFolderId, String name, String description,
			boolean hidden, ServiceContext serviceContext)
		throws PortalException, SystemException {

		// Folder

		User user = userPersistence.findByPrimaryKey(userId);
		parentFolderId = getParentFolderId(groupId, parentFolderId);
		Date now = new Date();

		validateFolder(groupId, parentFolderId, name);

		long folderId = counterLocalService.increment();

		DLFolder dlFolder = dlFolderPersistence.create(folderId);

		dlFolder.setUuid(serviceContext.getUuid());
		dlFolder.setGroupId(groupId);
		dlFolder.setCompanyId(user.getCompanyId());
		dlFolder.setUserId(user.getUserId());
		dlFolder.setCreateDate(serviceContext.getCreateDate(now));
		dlFolder.setModifiedDate(serviceContext.getModifiedDate(now));
		dlFolder.setRepositoryId(repositoryId);
		dlFolder.setMountPoint(mountPoint);
		dlFolder.setParentFolderId(parentFolderId);
		dlFolder.setName(name);
		dlFolder.setDescription(description);
		dlFolder.setHidden(hidden);
		dlFolder.setOverrideFileEntryTypes(false);
		dlFolder.setExpandoBridgeAttributes(serviceContext);

		dlFolderPersistence.update(dlFolder);

		// Resources

		if (serviceContext.isAddGroupPermissions() ||
			serviceContext.isAddGuestPermissions()) {

			addFolderResources(
				dlFolder, serviceContext.isAddGroupPermissions(),
				serviceContext.isAddGuestPermissions());
		}
		else {
			if (serviceContext.isDeriveDefaultPermissions()) {
				serviceContext.deriveDefaultPermissions(
					repositoryId, DLFolderConstants.getClassName());
			}

			addFolderResources(
				dlFolder, serviceContext.getGroupPermissions(),
				serviceContext.getGuestPermissions());
		}

		// Parent folder

		if (parentFolderId != DLFolderConstants.DEFAULT_PARENT_FOLDER_ID) {
			DLFolder parentDLFolder = dlFolderPersistence.findByPrimaryKey(
				parentFolderId);

			parentDLFolder.setLastPostDate(now);

			dlFolderPersistence.update(parentDLFolder);
		}

		// App helper

		dlAppHelperLocalService.addFolder(
			new LiferayFolder(dlFolder), serviceContext);

		return dlFolder;
	}

	/**
	 * @deprecated As of 6.2, replaced by more general {@link #addFolder(long,
	 *             long, long, boolean, long, String, String, boolean,
	 *             ServiceContext)}
	 */
	public DLFolder addFolder(
			long userId, long groupId, long repositoryId, boolean mountPoint,
			long parentFolderId, String name, String description,
			ServiceContext serviceContext)
		throws PortalException, SystemException {

		return addFolder(
			userId, groupId, repositoryId, mountPoint, parentFolderId, name,
			description, false, serviceContext);
	}

	public void deleteAll(long groupId)
		throws PortalException, SystemException {

		Group group = groupLocalService.getGroup(groupId);

		List<DLFolder> dlFolders = dlFolderPersistence.findByG_P(
			groupId, DLFolderConstants.DEFAULT_PARENT_FOLDER_ID);

		for (DLFolder dlFolder : dlFolders) {
			deleteFolder(dlFolder);
		}

		dlFileEntryLocalService.deleteFileEntries(
			groupId, DLFolderConstants.DEFAULT_PARENT_FOLDER_ID);

		dlFileShortcutLocalService.deleteFileShortcuts(
			groupId, DLFolderConstants.DEFAULT_PARENT_FOLDER_ID);

		try {
			DLStoreUtil.deleteDirectory(
				group.getCompanyId(), groupId, StringPool.BLANK);
		}
		catch (NoSuchDirectoryException nsde) {
			if (_log.isDebugEnabled()) {
				_log.debug(nsde.getMessage());
			}
		}
	}

	public void deleteFolder(long folderId)
		throws PortalException, SystemException {

		deleteFolder(folderId, true);
	}

	public void deleteFolder(long folderId, boolean includeTrashedEntries)
		throws PortalException, SystemException {

		DLFolder dlFolder = dlFolderPersistence.findByPrimaryKey(folderId);

		deleteFolder(dlFolder, includeTrashedEntries);
	}

	public DLFolder fetchFolder(long groupId, long parentFolderId, String name)
		throws SystemException {

		return dlFolderPersistence.fetchByG_P_N(groupId, parentFolderId, name);
	}

	public List<DLFolder> getCompanyFolders(long companyId, int start, int end)
		throws SystemException {

		return dlFolderPersistence.findByCompanyId(companyId, start, end);
	}

	public int getCompanyFoldersCount(long companyId) throws SystemException {
		return dlFolderPersistence.countByCompanyId(companyId);
	}

	/**
	 * @deprecated Replaced by {@link #getFileEntriesAndFileShortcuts(long,
	 *             long, QueryDefinition)}
	 */
	public List<Object> getFileEntriesAndFileShortcuts(
			long groupId, long folderId, int status, int start, int end)
		throws SystemException {

		QueryDefinition queryDefinition = new QueryDefinition(
			status, start, end, null);

		return getFileEntriesAndFileShortcuts(
			groupId, folderId, queryDefinition);
	}

	public List<Object> getFileEntriesAndFileShortcuts(
			long groupId, long folderId, QueryDefinition queryDefinition)
		throws SystemException {

		return dlFolderFinder.findFE_FS_ByG_F(
			groupId, folderId, queryDefinition);
	}

	/**
	 * @deprecated Replaced by {@link #getFileEntriesAndFileShortcutsCount(long,
	 *             long, QueryDefinition)}
	 */
	public int getFileEntriesAndFileShortcutsCount(
			long groupId, long folderId, int status)
		throws SystemException {

		QueryDefinition queryDefinition = new QueryDefinition(status);

		return getFileEntriesAndFileShortcutsCount(
			groupId, folderId, queryDefinition);
	}

	public int getFileEntriesAndFileShortcutsCount(
			long groupId, long folderId, QueryDefinition queryDefinition)
		throws SystemException {

		return dlFolderFinder.countFE_FS_ByG_F(
			groupId, folderId, queryDefinition);
	}

	public DLFolder getFolder(long folderId)
		throws PortalException, SystemException {

		return dlFolderPersistence.findByPrimaryKey(folderId);
	}

	public DLFolder getFolder(long groupId, long parentFolderId, String name)
		throws PortalException, SystemException {

		return dlFolderPersistence.findByG_P_N(groupId, parentFolderId, name);
	}

	public long getFolderId(long companyId, long folderId)
		throws SystemException {

		if (folderId != DLFolderConstants.DEFAULT_PARENT_FOLDER_ID) {

			// Ensure folder exists and belongs to the proper company

			DLFolder dlFolder = dlFolderPersistence.fetchByPrimaryKey(folderId);

			if ((dlFolder == null) || (companyId != dlFolder.getCompanyId())) {
				folderId = DLFolderConstants.DEFAULT_PARENT_FOLDER_ID;
			}
		}

		return folderId;
	}

	public List<DLFolder> getFolders(long groupId, long parentFolderId)
		throws SystemException {

		return getFolders(groupId, parentFolderId, true);
	}

	public List<DLFolder> getFolders(
			long groupId, long parentFolderId, boolean includeMountfolders)
		throws SystemException {

		if (includeMountfolders) {
			return dlFolderPersistence.findByG_P(groupId, parentFolderId);
		}
		else {
			return dlFolderPersistence.findByG_M_P_H(
				groupId, false, parentFolderId, false);
		}
	}

	public List<DLFolder> getFolders(
			long groupId, long parentFolderId, boolean includeMountfolders,
			int start, int end, OrderByComparator obc)
		throws SystemException {

		if (includeMountfolders) {
			return dlFolderPersistence.findByG_P(
				groupId, parentFolderId, start, end, obc);
		}
		else {
			return dlFolderPersistence.findByG_M_P_H(
				groupId, false, parentFolderId, false, start, end, obc);
		}
	}

	public List<DLFolder> getFolders(
			long groupId, long parentFolderId, int start, int end,
			OrderByComparator obc)
		throws SystemException {

		return getFolders(groupId, parentFolderId, true, start, end, obc);
	}

	/**
	 * @deprecated Replaced by {@link
	 *             #getFoldersAndFileEntriesAndFileShortcuts(long, long,
	 *             String[], boolean, QueryDefinition)}
	 */
	public List<Object> getFoldersAndFileEntriesAndFileShortcuts(
			long groupId, long folderId, int status,
			boolean includeMountFolders, int start, int end,
			OrderByComparator obc)
		throws SystemException {

		QueryDefinition queryDefinition = new QueryDefinition(
			status, start, end, obc);

		return getFoldersAndFileEntriesAndFileShortcuts(
			groupId, folderId, null, includeMountFolders, queryDefinition);
	}

	/**
	 * @deprecated Replaced by {@link
	 *             #getFoldersAndFileEntriesAndFileShortcutsCount(long, long,
	 *             String[], boolean, QueryDefinition)}
	 */
	public List<Object> getFoldersAndFileEntriesAndFileShortcuts(
			long groupId, long folderId, int status, String[] mimeTypes,
			boolean includeMountFolders, int start, int end,
			OrderByComparator obc)
		throws SystemException {

		QueryDefinition queryDefinition = new QueryDefinition(
			status, start, end, obc);

		return getFoldersAndFileEntriesAndFileShortcuts(
			groupId, folderId, mimeTypes, includeMountFolders, queryDefinition);
	}

	public List<Object> getFoldersAndFileEntriesAndFileShortcuts(
			long groupId, long folderId, String[] mimeTypes,
			boolean includeMountFolders, QueryDefinition queryDefinition)
		throws SystemException {

		return dlFolderFinder.findF_FE_FS_ByG_F_M_M(
			groupId, folderId, mimeTypes, includeMountFolders, queryDefinition);
	}

	/**
	 * @deprecated Replaced by {@link
	 *             #getFoldersAndFileEntriesAndFileShortcutsCount(long, long,
	 *             String[], boolean, QueryDefinition)}
	 */
	public int getFoldersAndFileEntriesAndFileShortcutsCount(
			long groupId, long folderId, int status,
			boolean includeMountFolders)
		throws SystemException {

		QueryDefinition queryDefinition = new QueryDefinition(status);

		return getFoldersAndFileEntriesAndFileShortcutsCount(
			groupId, folderId, null, includeMountFolders, queryDefinition);
	}

	/**
	 * @deprecated Replaced by {@link
	 *             #getFoldersAndFileEntriesAndFileShortcutsCount(long, long,
	 *             String[], boolean, QueryDefinition)}
	 */
	public int getFoldersAndFileEntriesAndFileShortcutsCount(
			long groupId, long folderId, int status, String[] mimeTypes,
			boolean includeMountFolders)
		throws SystemException {

		QueryDefinition queryDefinition = new QueryDefinition(status);

		return getFoldersAndFileEntriesAndFileShortcutsCount(
			groupId, folderId, mimeTypes, includeMountFolders, queryDefinition);
	}

	public int getFoldersAndFileEntriesAndFileShortcutsCount(
			long groupId, long folderId, String[] mimeTypes,
			boolean includeMountFolders, QueryDefinition queryDefinition)
		throws SystemException {

		return dlFolderFinder.countF_FE_FS_ByG_F_M_M(
			groupId, folderId, mimeTypes, includeMountFolders, queryDefinition);
	}

	public int getFoldersCount(long groupId, long parentFolderId)
		throws SystemException {

		return getFoldersCount(groupId, parentFolderId, true);
	}

	public int getFoldersCount(
			long groupId, long parentFolderId, boolean includeMountfolders)
		throws SystemException {

		if (includeMountfolders) {
			return dlFolderPersistence.countByG_P(groupId, parentFolderId);
		}
		else {
			return dlFolderPersistence.countByG_M_P_H(
				groupId, false, parentFolderId, false);
		}
	}

	public DLFolder getMountFolder(long repositoryId)
		throws PortalException, SystemException {

		return dlFolderPersistence.findByRepositoryId(repositoryId);
	}

	public List<DLFolder> getMountFolders(
			long groupId, long parentFolderId, int start, int end,
			OrderByComparator obc)
		throws SystemException {

		return dlFolderPersistence.findByG_M_P_H(
			groupId, true, parentFolderId, false, start, end, obc);
	}

	public int getMountFoldersCount(long groupId, long parentFolderId)
		throws SystemException {

		return dlFolderPersistence.countByG_M_P_H(
			groupId, true, parentFolderId, false);
	}

	public void getSubfolderIds(
			List<Long> folderIds, long groupId, long folderId)
		throws SystemException {

		List<DLFolder> dlFolders = dlFolderPersistence.findByG_P(
			groupId, folderId);

		for (DLFolder dlFolder : dlFolders) {
			folderIds.add(dlFolder.getFolderId());

			getSubfolderIds(
				folderIds, dlFolder.getGroupId(), dlFolder.getFolderId());
		}
	}

	public DLFolder moveFolder(
			long folderId, long parentFolderId, ServiceContext serviceContext)
		throws PortalException, SystemException {

		DLFolder dlFolder = dlFolderPersistence.findByPrimaryKey(folderId);

		parentFolderId = getParentFolderId(dlFolder, parentFolderId);

		validateFolder(
			dlFolder.getFolderId(), dlFolder.getGroupId(), parentFolderId,
			dlFolder.getName());

		dlFolder.setModifiedDate(serviceContext.getModifiedDate(null));
		dlFolder.setParentFolderId(parentFolderId);
		dlFolder.setExpandoBridgeAttributes(serviceContext);

		dlFolderPersistence.update(dlFolder);

		dlAppHelperLocalService.moveFolder(new LiferayFolder(dlFolder));

		return dlFolder;
	}

	public DLFolder updateFolder(
			long folderId, long parentFolderId, String name, String description,
			long defaultFileEntryTypeId, List<Long> fileEntryTypeIds,
			boolean overrideFileEntryTypes, ServiceContext serviceContext)
		throws PortalException, SystemException {

		// File entry types

		DLFolder dlFolder = null;

		if (folderId > DLFolderConstants.DEFAULT_PARENT_FOLDER_ID) {
			dlFolder = dlFolderLocalService.updateFolderAndFileEntryTypes(
				folderId, parentFolderId, name, description,
				defaultFileEntryTypeId, fileEntryTypeIds,
				overrideFileEntryTypes, serviceContext);

			dlFileEntryTypeLocalService.cascadeFileEntryTypes(
				serviceContext.getUserId(), dlFolder);
		}

		// Workflow definitions

		List<ObjectValuePair<Long, String>> workflowDefinitionOVPs =
			new ArrayList<ObjectValuePair<Long, String>>();

		if (fileEntryTypeIds.isEmpty()) {
			fileEntryTypeIds.add(
				DLFileEntryTypeConstants.FILE_ENTRY_TYPE_ID_ALL);
		}
		else {
			workflowDefinitionOVPs.add(
				new ObjectValuePair<Long, String>(
					DLFileEntryTypeConstants.FILE_ENTRY_TYPE_ID_ALL,
					StringPool.BLANK));
		}

		for (long fileEntryTypeId : fileEntryTypeIds) {
			String workflowDefinition = ParamUtil.getString(
				serviceContext, "workflowDefinition" + fileEntryTypeId);

			workflowDefinitionOVPs.add(
				new ObjectValuePair<Long, String>(
					fileEntryTypeId, workflowDefinition));
		}

		workflowDefinitionLinkLocalService.updateWorkflowDefinitionLinks(
			serviceContext.getUserId(), serviceContext.getCompanyId(),
			serviceContext.getScopeGroupId(), DLFolder.class.getName(),
			folderId, workflowDefinitionOVPs);

		return dlFolder;
	}

	public DLFolder updateFolder(
			long folderId, String name, String description,
			long defaultFileEntryTypeId, List<Long> fileEntryTypeIds,
			boolean overrideFileEntryTypes, ServiceContext serviceContext)
		throws PortalException, SystemException {

		return updateFolder(
			folderId, folderId, name, description, defaultFileEntryTypeId,
			fileEntryTypeIds, overrideFileEntryTypes, serviceContext);
	}

	public DLFolder updateFolderAndFileEntryTypes(
			long folderId, long parentFolderId, String name, String description,
			long defaultFileEntryTypeId, List<Long> fileEntryTypeIds,
			boolean overrideFileEntryTypes, ServiceContext serviceContext)
		throws PortalException, SystemException {

		// Folder

		if (!overrideFileEntryTypes) {
			fileEntryTypeIds = Collections.emptyList();
		}

		DLFolder dlFolder = dlFolderPersistence.findByPrimaryKey(folderId);

		parentFolderId = getParentFolderId(dlFolder, parentFolderId);

		validateFolder(folderId, dlFolder.getGroupId(), parentFolderId, name);

		dlFolder.setModifiedDate(serviceContext.getModifiedDate(null));
		dlFolder.setParentFolderId(parentFolderId);
		dlFolder.setName(name);
		dlFolder.setDescription(description);
		dlFolder.setExpandoBridgeAttributes(serviceContext);
		dlFolder.setOverrideFileEntryTypes(overrideFileEntryTypes);
		dlFolder.setDefaultFileEntryTypeId(defaultFileEntryTypeId);

		dlFolderPersistence.update(dlFolder);

		// File entry types

		if (fileEntryTypeIds != null) {
			dlFileEntryTypeLocalService.updateFolderFileEntryTypes(
				dlFolder, fileEntryTypeIds, defaultFileEntryTypeId,
				serviceContext);
		}

		// App helper

		dlAppHelperLocalService.updateFolder(
			new LiferayFolder(dlFolder), serviceContext);

		return dlFolder;
	}

	public void updateLastPostDate(long folderId, Date lastPostDate)
		throws PortalException, SystemException {

		DLFolder dlFolder = dlFolderPersistence.findByPrimaryKey(folderId);

		dlFolder.setLastPostDate(lastPostDate);

		dlFolderPersistence.update(dlFolder);
	}

	public DLFolder updateStatus(
			long userId, long folderId, int status,
			Map<String, Serializable> workflowContext,
			ServiceContext serviceContext)
		throws PortalException, SystemException {

		// Folder

		User user = userPersistence.findByPrimaryKey(userId);

		DLFolder dlFolder = dlFolderPersistence.findByPrimaryKey(folderId);

		if (dlFolder.isInTrash() &&
			(status == WorkflowConstants.STATUS_APPROVED)) {

			dlFolder.setName(TrashUtil.getOriginalTitle(dlFolder.getName()));
		}

		dlFolder.setStatus(status);
		dlFolder.setStatusByUserId(user.getUserId());
		dlFolder.setStatusByUserName(user.getFullName());
		dlFolder.setStatusDate(new Date());

		dlFolderPersistence.update(dlFolder);

		// Folders, file entries, and file shortcuts

		QueryDefinition queryDefinition = new QueryDefinition(
			WorkflowConstants.STATUS_ANY);

		List<Object> foldersAndFileEntriesAndFileShortcuts =
			getFoldersAndFileEntriesAndFileShortcuts(
				dlFolder.getGroupId(), folderId, null, false, queryDefinition);

		dlAppHelperLocalService.updateStatuses(
			user, foldersAndFileEntriesAndFileShortcuts, status);

		// Trash

		if (status == WorkflowConstants.STATUS_IN_TRASH) {
			UnicodeProperties typeSettingsProperties = new UnicodeProperties();

			typeSettingsProperties.put("title", dlFolder.getName());

			trashEntryLocalService.addTrashEntry(
				userId, dlFolder.getGroupId(), DLFolderConstants.getClassName(),
				dlFolder.getFolderId(), WorkflowConstants.STATUS_APPROVED, null,
				typeSettingsProperties);
		}

		return dlFolder;
	}

	protected void addFolderResources(
			DLFolder dlFolder, boolean addGroupPermissions,
			boolean addGuestPermissions)
		throws PortalException, SystemException {

		resourceLocalService.addResources(
			dlFolder.getCompanyId(), dlFolder.getGroupId(),
			dlFolder.getUserId(), DLFolder.class.getName(),
			dlFolder.getFolderId(), false, addGroupPermissions,
			addGuestPermissions);
	}

	protected void addFolderResources(
			DLFolder dlFolder, String[] groupPermissions,
			String[] guestPermissions)
		throws PortalException, SystemException {

		resourceLocalService.addModelResources(
			dlFolder.getCompanyId(), dlFolder.getGroupId(),
			dlFolder.getUserId(), DLFolder.class.getName(),
			dlFolder.getFolderId(), groupPermissions, guestPermissions);
	}

	protected void addFolderResources(
			long folderId, boolean addGroupPermissions,
			boolean addGuestPermissions)
		throws PortalException, SystemException {

		DLFolder dlFolder = dlFolderPersistence.findByPrimaryKey(folderId);

		addFolderResources(dlFolder, addGroupPermissions, addGuestPermissions);
	}

	protected void addFolderResources(
			long folderId, String[] groupPermissions, String[] guestPermissions)
		throws PortalException, SystemException {

		DLFolder dlFolder = dlFolderPersistence.findByPrimaryKey(folderId);

		addFolderResources(dlFolder, groupPermissions, guestPermissions);
	}

	protected void deleteFolder(DLFolder dlFolder)
		throws PortalException, SystemException {

		deleteFolder(dlFolder, true);
	}

	protected void deleteFolder(
			DLFolder dlFolder, boolean includeTrashedEntries)
		throws PortalException, SystemException {

		// Folders

		List<DLFolder> dlFolders = dlFolderPersistence.findByG_P(
			dlFolder.getGroupId(), dlFolder.getFolderId());

		for (DLFolder curDLFolder : dlFolders) {
			if (includeTrashedEntries || !curDLFolder.isInTrash()) {
				deleteFolder(curDLFolder, includeTrashedEntries);
			}
		}

		// Resources

		resourceLocalService.deleteResource(
			dlFolder.getCompanyId(), DLFolder.class.getName(),
			ResourceConstants.SCOPE_INDIVIDUAL, dlFolder.getFolderId());

		// WebDAVProps

		webDAVPropsLocalService.deleteWebDAVProps(
			DLFolder.class.getName(), dlFolder.getFolderId());

		// File entries

		dlFileEntryLocalService.deleteFileEntries(
			dlFolder.getGroupId(), dlFolder.getFolderId(),
			includeTrashedEntries);

		// File entry types

		dlFileEntryTypeLocalService.unsetFolderFileEntryTypes(
			dlFolder.getFolderId());

		// File shortcuts

		dlFileShortcutLocalService.deleteFileShortcuts(
			dlFolder.getGroupId(), dlFolder.getFolderId(),
			includeTrashedEntries);

		// Expando

		expandoValueLocalService.deleteValues(
			DLFolder.class.getName(), dlFolder.getFolderId());

		// App helper

		dlAppHelperLocalService.deleteFolder(new LiferayFolder(dlFolder));

		// Folder

		dlFolderPersistence.remove(dlFolder);

		// Directory

		try {
			DLStoreUtil.deleteDirectory(
				dlFolder.getCompanyId(), dlFolder.getFolderId(),
				StringPool.BLANK);
		}
		catch (NoSuchDirectoryException nsde) {
			if (_log.isDebugEnabled()) {
				_log.debug(nsde.getMessage());
			}
		}
	}

	protected long getParentFolderId(DLFolder dlFolder, long parentFolderId)
		throws SystemException {

		if (parentFolderId == DLFolderConstants.DEFAULT_PARENT_FOLDER_ID) {
			return parentFolderId;
		}

		if (dlFolder.getFolderId() == parentFolderId) {
			return dlFolder.getParentFolderId();
		}
		else {
			DLFolder parentDLFolder = dlFolderPersistence.fetchByPrimaryKey(
				parentFolderId);

			if ((parentDLFolder == null) ||
				(dlFolder.getGroupId() != parentDLFolder.getGroupId())) {

				return dlFolder.getParentFolderId();
			}

			List<Long> subfolderIds = new ArrayList<Long>();

			getSubfolderIds(
				subfolderIds, dlFolder.getGroupId(), dlFolder.getFolderId());

			if (subfolderIds.contains(parentFolderId)) {
				return dlFolder.getParentFolderId();
			}

			return parentFolderId;
		}
	}

	protected long getParentFolderId(long groupId, long parentFolderId)
		throws SystemException {

		if (parentFolderId != DLFolderConstants.DEFAULT_PARENT_FOLDER_ID) {
			DLFolder parentDLFolder = dlFolderPersistence.fetchByPrimaryKey(
				parentFolderId);

			if ((parentDLFolder == null) ||
				(groupId != parentDLFolder.getGroupId())) {

				parentFolderId = DLFolderConstants.DEFAULT_PARENT_FOLDER_ID;
			}
		}

		return parentFolderId;
	}

	protected void validateFolder(
			long folderId, long groupId, long parentFolderId, String name)
		throws PortalException, SystemException {

		validateFolderName(name);

		try {
			dlFileEntryLocalService.getFileEntry(groupId, parentFolderId, name);

			throw new DuplicateFileException(name);
		}
		catch (NoSuchFileEntryException nsfee) {
		}

		DLFolder dlFolder = dlFolderPersistence.fetchByG_P_N(
			groupId, parentFolderId, name);

		if ((dlFolder != null) && (dlFolder.getFolderId() != folderId)) {
			throw new DuplicateFolderNameException(name);
		}
	}

	protected void validateFolder(
			long groupId, long parentFolderId, String name)
		throws PortalException, SystemException {

		long folderId = 0;

		validateFolder(folderId, groupId, parentFolderId, name);
	}

	protected void validateFolderName(String name) throws PortalException {
		if (!AssetUtil.isValidWord(name)) {
			throw new FolderNameException();
		}
	}

	private static Log _log = LogFactoryUtil.getLog(
		DLFolderLocalServiceImpl.class);

}