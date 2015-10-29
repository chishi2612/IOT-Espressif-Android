package com.espressif.iot.model.group;

import java.util.ArrayList;
import java.util.List;
import java.util.Vector;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;

import org.apache.log4j.Logger;

import android.content.Intent;
import android.support.v4.content.LocalBroadcastManager;

import com.espressif.iot.action.group.EspActionGroupCreateInternet;
import com.espressif.iot.action.group.EspActionGroupDeleteInternet;
import com.espressif.iot.action.group.EspActionGroupMoveDeviceInternet;
import com.espressif.iot.action.group.EspActionGroupRemoveDeviceInternet;
import com.espressif.iot.action.group.EspActionGroupRenameInternet;
import com.espressif.iot.action.group.IEspActionGroupCreateInternet;
import com.espressif.iot.action.group.IEspActionGroupDeleteInternet;
import com.espressif.iot.action.group.IEspActionGroupMoveDeviceInternet;
import com.espressif.iot.action.group.IEspActionGroupRemoveDeviceInternet;
import com.espressif.iot.action.group.IEspActionGroupRenameInternet;
import com.espressif.iot.base.api.EspBaseApiUtil;
import com.espressif.iot.base.application.EspApplication;
import com.espressif.iot.db.EspGroupDBManager;
import com.espressif.iot.db.greenrobot.daos.GroupDB;
import com.espressif.iot.device.IEspDevice;
import com.espressif.iot.group.IEspGroup;
import com.espressif.iot.user.IEspUser;
import com.espressif.iot.user.builder.BEspUser;
import com.espressif.iot.util.EspStrings;

public class EspGroupHandler
{
    private static EspGroupHandler instance;
    
    public static EspGroupHandler getInstance()
    {
        if (instance == null)
        {
            instance = new EspGroupHandler();
        }
        
        return instance;
    }
    
    private final Logger log = Logger.getLogger(getClass());
    
    private IEspUser mUser;
    
    private EspGroupDBManager mDBManager;
    
    private List<IEspGroup> mSyncCloudGroups;
    
    private Future<?> mFuture;
    
    private LinkedBlockingQueue<Boolean> mHandleQueue;
    
    public EspGroupHandler()
    {
        mUser = BEspUser.getBuilder().getInstance();
        mDBManager = EspGroupDBManager.getInstance();
        
        mSyncCloudGroups = new Vector<IEspGroup>();
        mHandleQueue = new LinkedBlockingQueue<Boolean>();
    }
    
    public synchronized void updateSynchronizeCloudGroups(List<IEspGroup> list)
    {
        mSyncCloudGroups.clear();
        for (IEspGroup group : list)
        {
            // group id = 0 means that the user didn't start to use grouping
            if (group.getId() != 0)
            {
                mSyncCloudGroups.add(group);
            }
        }
        updateCloudDB();
    }
    
    public synchronized void clearSynchronizeCloudGroups()
    {
        mSyncCloudGroups.clear();
    }
    
    private void updateCloudDB()
    {
        List<GroupDB> cloudGroupDBs = mDBManager.getUserDBCloudGroup(mUser.getUserKey());
        List<IEspGroup> syncCloudGroups = new ArrayList<IEspGroup>();
        syncCloudGroups.addAll(mSyncCloudGroups);
        for (GroupDB groupDB : cloudGroupDBs)
        {
            EspGroup dbGroup = new EspGroup();
            dbGroup.setId(groupDB.getId());
            dbGroup.setState(groupDB.getState());
            dbGroup.setName(groupDB.getName());
            int index = syncCloudGroups.indexOf(dbGroup);
            if (index >= 0)
            {
                // Server exist & DB cloud exist.
                IEspGroup cloudGroup = syncCloudGroups.get(index);
                if (!dbGroup.isStateRenamed() && !dbGroup.getName().equals(cloudGroup.getName()))
                {
                    // Not rename state, modify db group name
                    mDBManager.updateName(cloudGroup.getId(), cloudGroup.getName());
                }
                
                // Update devices
                List<String> bssids = new ArrayList<String>();
                List<String> removeBssids = mDBManager.getDeviceBssids(groupDB.getRemoveDeviceBssids());
                for (IEspDevice device : cloudGroup.getDeviceList())
                {
                    // Filter deleted state device
                    if (!removeBssids.contains(device.getBssid()))
                    {
                        bssids.add(device.getBssid());
                    }
                }
                mDBManager.updateCloudBssids(cloudGroup.getId(), bssids);
                
                // Process complete, remove group from syncCloudGroups
                syncCloudGroups.remove(index);
            }
            else
            {
                // Server not exist, DB cloud exist, delete from DB
                mDBManager.delete(dbGroup.getId());
            }
        }
        
        // Server exist, DB not exist, create new in DB
        for (IEspGroup group : syncCloudGroups)
        {
            List<String> cloudBssids = group.generateDeviceBssidList();
            long groupId = group.getId();
            String groupName = group.getName();
            String userKey = mUser.getUserKey();
            int state = group.getStateValue();
            String cloudBssidsText = mDBManager.getDeviceBssidsText(cloudBssids);
            
            GroupDB groupDB = new GroupDB(groupId, groupName, userKey, state, "", cloudBssidsText, "");
            mDBManager.insertOrReplace(groupDB);
        }
    }
    
    /**
     * If the task thread hasn't started, start the thread. Then run the group task at least once.
     */
    public void call()
    {
        synchronized (mHandleQueue)
        {
            if (mFuture == null)
            {
                mFuture = EspBaseApiUtil.submit(new GroupTask());
            }
            if (mHandleQueue.size() < 2)
            {
                mHandleQueue.add(true);
            }
        }
    }
    
    /**
     * Clear the group task which is waiting for starting
     */
    public void cancel()
    {
        mHandleQueue.clear();
    }
    
    /**
     * Stop the group task thread
     */
    public void finish()
    {
        cancel();
        mHandleQueue.add(false);
        if (mFuture != null)
        {
            mFuture.cancel(true);
            mFuture = null;
        }
    }
    
    private class GroupTask implements Runnable
    {
        @Override
        public void run()
        {
            while (true)
            {
                try
                {
                    boolean runTask = mHandleQueue.take();
                    log.debug("GroupTask mHandleQueue take " + runTask);
                    if (!runTask)
                    {
                        mFuture = null;
                        return;
                    }
                }
                catch (InterruptedException e)
                {
                    log.warn("GroupTask Interrupted mHandleQueue");
                    mFuture = null;
                    return;
                }
                
                if (!mUser.isLogin())
                {
                    continue;
                }
                
                log.debug("GroupTask start...");
                executeTask();
                log.debug("GroupTask end...");
            }
            
        }
        
        private void executeTask()
        {
            String userKey = mUser.getUserKey();
            if (userKey == null)
            {
                userKey = "";
            }
            List<GroupDB> localGroups = mDBManager.getUserDBLocalGroup(userKey);
            for (GroupDB groupDB : localGroups)
            {
                // DB local exist, post create command
                log.debug("GroupTask create cloud group : id = " + groupDB.getId() + " || name = " + groupDB.getName());
                IEspActionGroupCreateInternet createAction = new EspActionGroupCreateInternet();
                long newGroupId = createAction.doActionCreateGroupInternet(groupDB.getUserKey(), groupDB.getName());
                if (newGroupId > 0)
                {
                    log.debug("GroupTask create cloud group suc : id = " + newGroupId + " || name = "
                        + groupDB.getName());
                    GroupDB oldGroupDB = mDBManager.getGroupDB(groupDB.getId());
                    GroupDB newGroupDB =
                        new GroupDB(newGroupId, oldGroupDB.getName(), oldGroupDB.getUserKey(), 0,
                            oldGroupDB.getLocalDeviceBssids(), "", "");
                    mDBManager.insertOrReplace(newGroupDB);
                    mDBManager.delete(oldGroupDB.getId());
                    
                    Intent intent = new Intent(EspStrings.Action.CREATE_NEW_CLOUD_GROUP);
                    intent.putExtra(EspStrings.Key.KEY_GROUP_ID_OLD, oldGroupDB.getId());
                    intent.putExtra(EspStrings.Key.KEY_GROUP_ID_NEW, newGroupId);
                    LocalBroadcastManager.getInstance(EspApplication.sharedInstance()).sendBroadcast(intent);
                }
            }
            
            List<GroupDB> cloudGroups = mDBManager.getUserDBCloudGroup(userKey);
            for (GroupDB groupDB : cloudGroups)
            {
                EspGroup espGroup = new EspGroup();
                espGroup.setId(groupDB.getId());
                espGroup.setName(groupDB.getName());
                espGroup.setState(groupDB.getState());
                if (espGroup.isStateDeleted())
                {
                    // DB cloud group is deleted state, post delete command
                    log.debug("GroupTask delete cloud group : id = " + espGroup.getId() + " || name = "
                        + espGroup.getName());
                    IEspActionGroupDeleteInternet deleteAction = new EspActionGroupDeleteInternet();
                    if (deleteAction.doActionDeleteGroupInternet(userKey, groupDB.getId()))
                    {
                        log.debug("GroupTask delete cloud group suc : id = " + espGroup.getId());
                        mDBManager.delete(groupDB.getId());
                    }
                    continue;
                }
                
                if (espGroup.isStateRenamed())
                {
                    // DB cloud group is renamed state, post rename command
                    log.debug("GroupTask rename cloud group : id = " + espGroup.getId() + " || name = "
                        + espGroup.getName());
                    IEspActionGroupRenameInternet renameAction = new EspActionGroupRenameInternet();
                    if (renameAction.doActionRenameGroupInternet(userKey, groupDB.getId(), groupDB.getName()))
                    {
                        log.debug("GroupTask rename cloud group suc : id = " + espGroup.getId());
                        espGroup.clearState(EspGroup.State.RENAMED);
                        mDBManager.updateState(espGroup.getId(), espGroup.getStateValue());
                    }
                }
                
                List<String> localBssids = mDBManager.getDeviceBssids(groupDB.getLocalDeviceBssids());
                List<String> cloudBssids = mDBManager.getDeviceBssids(groupDB.getCloudDeviceBssids());
                List<String> removeBssids = mDBManager.getDeviceBssids(groupDB.getRemoveDeviceBssids());
                boolean localModified = false;
                boolean cloudModified = false;
                boolean removeModified = false;
                for (int i = 0; i < cloudBssids.size(); i++)
                {
                    String cloudBssid = cloudBssids.get(i);
                    if (localBssids.contains(cloudBssid))
                    {
                        // local device exist, cloud device exist.
                        // delete local device
                        localBssids.remove(cloudBssid);
                        localModified = true;
                        log.debug("GroupTask device exist in cloud db, remove local bssid = " + cloudBssid);
                    }
                    if (removeBssids.contains(cloudBssid))
                    {
                        // the device is remove state. Delete cloud device
                        cloudBssids.remove(i);
                        i--;
                        cloudModified = true;
                        log.debug("GroupTask device exist in remove db, remove cloud bssid = " + cloudBssid);
                    }
                }
                List<IEspDevice> userDevices = mUser.getDeviceList();
                for (IEspDevice device : userDevices)
                {
                    for (int i = 0; i < localBssids.size(); i++)
                    {
                        String localBssid = localBssids.get(i);
                        if (localBssid.equals(device.getBssid()))
                        {
                            // local device exist, cloud device not exist, user has this device
                            // post move device command.
                            log.debug("GroupTask move device to direct group. device name = " + device.getName()
                                + " || group name = " + groupDB.getName());
                            IEspActionGroupMoveDeviceInternet moveAction = new EspActionGroupMoveDeviceInternet();
                            if (moveAction.doActionMoveDeviceIntoGroupInternet(userKey,
                                device.getId(),
                                groupDB.getId(),
                                true))
                            {
                                log.debug("GroupTask move device to direct group suc");
                                localBssids.remove(i);
                                cloudBssids.add(localBssid);
                                localModified = true;
                                cloudModified = true;
                                break;
                            }
                        }
                    }
                    
                    for (int i = 0; i < removeBssids.size(); i++)
                    {
                        String removeBssid = removeBssids.get(i);
                        if (removeBssid.equals(device.getBssid()))
                        {
                            log.debug("GroupTask remove device from group. device name = " + device.getName()
                                + " || group name = " + groupDB.getName());
                            IEspActionGroupRemoveDeviceInternet removeAction = new EspActionGroupRemoveDeviceInternet();
                            if (removeAction.doActionRemoveDevicefromGroupInternet(userKey,
                                device.getId(),
                                groupDB.getId()))
                            {
                                log.debug("GroupTask remove device from group suc");
                                removeBssids.remove(i);
                                removeModified = true;
                                break;
                            }
                        }
                    }
                }
                
                if (localModified)
                {
                    mDBManager.updateLocalBssids(groupDB.getId(), localBssids);
                }
                if (cloudModified)
                {
                    mDBManager.updateCloudBssids(groupDB.getId(), cloudBssids);
                }
                if (removeModified)
                {
                    mDBManager.updateRemoveBssids(groupDB.getId(), removeBssids);
                }
            }
        }
    }
}