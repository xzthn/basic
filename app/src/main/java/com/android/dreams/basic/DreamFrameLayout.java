package com.android.dreams.basic;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.Message;
import android.service.dreams.DreamService;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.widget.FrameLayout;

import com.android.dreams.basic.bean.ScreenSaverBean;
import com.android.dreams.basic.util.ClickActionUtil;
import com.android.dreams.basic.util.CommonUtil;
import com.android.dreams.basic.util.GsonUtil;
import com.android.dreams.basic.util.IOHelper;
import com.android.dreams.basic.util.SharedPreferenceUtil;
import com.lunzn.tool.log.LogUtil;
import com.smart.localfile.LocalFileCRUDUtils;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.ListIterator;

/**
 * Desc: 显示图片布局
 * <p>
 * Author: xiezhitao
 * PackageName: com.lzui.screensaver
 * ProjectName: ScreenSave
 * Date: 2019/3/1 10:10
 */
public class DreamFrameLayout extends FrameLayout {

    public static final String TAG = "DreamFrameLayout";

    /** 屏保服务 */
    private DreamService mDream;

    /** 显示图片容器 */
    private ImageViewer mImageViewer;

    /** 主要工作线程 */
    private final Flipper mFlipper;

    /** 屏保图片切换时间 */
    private long mDropPeriod;

    /** 图片和异步任务的限制数量 */
    private final int mBitmapQueueLimit;

    /** 存储图片的Map */
    private final HashMap<String, Bitmap> mBitmapStore;

    /** 存储异步任务集合 */
    private final LinkedList<PhotoLoadTask> mBitmapLoaders;

    /** 存储图片路劲集合 */
    private final ArrayList<File> mBitmapPath;

    /** 当前图片位置 */
    private int mIndex;

    /** 图片加载路劲 */
    private String mLoadDir;

    /** 布局是否已经挂载 */
    public static boolean isonAttachedToWindow;

    private String[] defaultImg = getResources().getStringArray(R.array.default_img);

    /** 实体类 */
    private ScreenSaverBean mScreenSaverBean = null;

    /** 是否是本地数据 */
    private boolean mIsLocal;

    /** 本地数据 */
    private ArrayList<String> mLocalData;

    private Handler mHandler = new Handler(new Handler.Callback() {
        @Override
        public boolean handleMessage(Message msg) {
            if (msg.what == 0x1001) {
                int tempIndex = mIndex + 1;
                if (tempIndex >= mLocalData.size()) {
                    mIndex = 0;
                } else {
                    mIndex++;
                }
                try {
                    Bitmap bitmap;
                    bitmap = BitmapFactory.decodeResource(getResources(), Integer.valueOf(mLocalData.get(mIndex)));

                    mImageViewer.setBitmap(bitmap);
                } catch (Exception e) {
                    e.printStackTrace();
                }
                mHandler.sendEmptyMessageDelayed(0x1001, 10000);
                return true;
            }
            return false;
        }
    });



    public DreamFrameLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        mBitmapQueueLimit = 30;
        mFlipper = new Flipper();
        mBitmapStore = new HashMap<>();
        mBitmapLoaders = new LinkedList<>();
        mBitmapPath = new ArrayList<>();
        mIndex = 0;
        mLoadDir = new String();
        mDropPeriod = 10000;
        mLocalData = new ArrayList<>();
    }

    @Override
    public void onAttachedToWindow() {
        mImageViewer = this.findViewById(R.id.imageViewer);
        isonAttachedToWindow = true;
        LogUtil.d(TAG, " -----onAttachedToWindow--------" + isonAttachedToWindow);
        mHandler.post(mFlipper);
        super.onAttachedToWindow();
    }

    @Override
    protected void onDetachedFromWindow() {
        isonAttachedToWindow = false;
        LogUtil.d(TAG, " -----onDetachedFromWindow--------" + isonAttachedToWindow);

        if (mHandler.hasMessages(0x1001)) {
            mHandler.removeMessages(0x1001);
        }

        mHandler.removeCallbacks(mFlipper);

        if (mScreenSaverBean != null) {

            CommonUtil.setEnabled(mScreenSaverBean.getSwitchFlag() == 1);
        } else {
            boolean isOPen = SharedPreferenceUtil.getBoolean("isOPen", false);
            LogUtil.d(TAG, " -----默认数据----是否打开屏保 " + isOPen);
            CommonUtil.setEnabled(isOPen);
        }
        if (mBitmapLoaders != null && !mBitmapLoaders.isEmpty()) {
            for (ListIterator<PhotoLoadTask> i = mBitmapLoaders.listIterator(0);
                 i.hasNext(); ) {
                PhotoLoadTask loader = i.next();
                if (loader.getStatus() == AsyncTask.Status.FINISHED) {
                    i.remove();
                }
            }
        }
        if (mBitmapStore != null) {
            for (Bitmap bitmap : mBitmapStore.values()) {
                if (bitmap != null) {
                    bitmap.recycle();
                }
            }
        }


        super.onDetachedFromWindow();
    }

    public void setDream(DreamService dream) {
        mDream = dream;
    }


    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        LogUtil.d(TAG, "onKeyDown keyCode " + keyCode);
        if (mIsLocal) {
//            mHandler.removeCallbacks(mFlipper);
//            mHandler.removeMessages(0x1001);
            if (mDream != null) {
                mDream.finish();
            }
            return true;
        }
        if (KeyEvent.ACTION_DOWN == event.getAction()) {
            switch (keyCode) {

                case KeyEvent.KEYCODE_DPAD_CENTER:
                    for (int i = 0; i < mScreenSaverBean.getSlider().size(); i++) {
                        String name = mScreenSaverBean.getSlider().get(i).getImg_url().hashCode() + "." + 0;

                        if (mBitmapPath.get(mIndex).getName().equalsIgnoreCase(name)) {
                            LogUtil.d(TAG, "name " + name + " mBitmapPath.get(" + mIndex + ").getName() " +
                                    mBitmapPath.get(mIndex).getName());

                            ClickActionUtil.openClickDataNoReplace(mScreenSaverBean.getSlider().get(i), getContext());
                            exitDream();
                        }
                    }
                    return true;

                default:
                    LogUtil.d(TAG, "点击了 非确定 键" + keyCode);
                    exitDream();
                    return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    public void exitDream(){
        if (mDream != null) {
            mHandler.removeCallbacks(mFlipper);
            mDream.finish();
        }
    }
    /**
     * 数据跟新方法
     */
    public void setNewData() {

        LogUtil.d(TAG, " ==========更新数据========== ：");
        mBitmapPath.clear();
//        mLocalData.clear();
        mIndex = 0;
        mHandler.removeCallbacks(mFlipper);
        mHandler.postDelayed(mFlipper, 100);
        mIsLocal = false;
        if (mHandler.hasMessages(0x1001)) {
            mHandler.removeMessages(0x1001);
        }
    }

    private void setDefaultPic() {
        mIsLocal = true;
        mLocalData.clear();
//        mLocalData.add(0, String.valueOf(R.drawable.bg1));
//        mLocalData.add(1, String.valueOf(R.drawable.bg2));
//        mLocalData.add(2, String.valueOf(R.drawable.bg3));
        mLocalData.add(0, String.valueOf(R.drawable.paint1));
        mLocalData.add(1, String.valueOf(R.drawable.paint2));
        mLocalData.add(2, String.valueOf(R.drawable.paint3));
        mLocalData.add(3, String.valueOf(R.drawable.paint4));
        mLocalData.add(4, String.valueOf(R.drawable.paint5));
        mLocalData.add(5, String.valueOf(R.drawable.paint6));
        mLocalData.add(6, String.valueOf(R.drawable.paint7));

        mHandler.sendEmptyMessage(0x1001);
    }

    private void loadData() {
        String version = SharedPreferenceUtil.getString("version", "0");
        if (!version.equalsIgnoreCase("0")) {
            mLoadDir = IOHelper.getFilePath(getContext(), version);
            LogUtil.d(TAG, " 图片路劲 ：" + mLoadDir);

            File file = new File(mLoadDir);
            if (file.exists() && file.isDirectory()) {
                File[] files = file.listFiles();
                for (int i = 0; i < files.length; i++) {

                    LogUtil.i(TAG, "files[" + i + "]  " + files[i].getName());

                    if (files[i].getName().endsWith("json")) {

                        String data = LocalFileCRUDUtils.readFile(mLoadDir + "/data.json");

                        try {
                            JSONObject jsonObject = new JSONObject(data);


                            if (!jsonObject.has("Z") || (int) jsonObject.get("Z") != 0) {

                                LogUtil.i(TAG, "没有 z 字段 ？");
                                return;
                            }

                            mScreenSaverBean = GsonUtil.fromJson(jsonObject.optString("screenSaver"),
                                    ScreenSaverBean.class);

                            LogUtil.i(TAG, "mScreenSaverBean " + mScreenSaverBean.toString());
                            mDropPeriod = mScreenSaverBean.getInterval();

                        } catch (Exception e) {
                            e.printStackTrace();
                        }


                    } else {
                        mBitmapPath.add(files[i]);

                    }
                }
                LogUtil.i(TAG, "mBitmapPath size " + mBitmapPath.size());
                maybeLoadMore();

            } else {
                LogUtil.d(TAG, mLoadDir + "文件夹不存在,加载默认图片");
                setDefaultPic();

            }


        } else {
            LogUtil.d(TAG, "版本号为0，加载默认图片");
            setDefaultPic();
        }


    }

    /**
     * 启动加载图片异步任务
     */
    private void maybeLoadMore() {
        LogUtil.d(TAG, " mBitmapLoaders.size " + mBitmapLoaders.size());
        if (!mBitmapLoaders.isEmpty()) {
            for (ListIterator<PhotoLoadTask> i = mBitmapLoaders.listIterator(0);
                 i.hasNext(); ) {
                PhotoLoadTask loader = i.next();
                if (loader.getStatus() == AsyncTask.Status.FINISHED) {
                    i.remove();
                    LogUtil.d(TAG, " -----remove--------");
                }
            }
        }
        LogUtil.d(TAG, "mBitmapStore " + mBitmapStore.size());
        if ((mBitmapLoaders.size() + mBitmapStore.size()) < mBitmapQueueLimit) {
            PhotoLoadTask task = new PhotoLoadTask();
            mBitmapLoaders.offer(task);
            int tempIndex = mIndex + 1;
            if (tempIndex >= mBitmapPath.size()) {
                mIndex = 0;
            } else {
                mIndex++;
            }

            try {
                task.execute(mBitmapPath.get(mIndex).getAbsolutePath());
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * 主要工作线程
     */
    class Flipper implements Runnable {
        @Override
        public void run() {

            if (isonAttachedToWindow) {

                if (mBitmapPath.isEmpty()) {
                    loadData();
                } else {
                    LogUtil.d(TAG, "图片路劲不为空");
                    maybeLoadMore();
                }
            }

        }
    }

    /**
     * 图片加载异步任务
     */
    private class PhotoLoadTask extends AsyncTask<String, Void, Bitmap> {

        public PhotoLoadTask() {

        }

        @Override
        protected Bitmap doInBackground(String... strings) {
            Bitmap decodedPhoto = null;
            File file = new File(strings[0]);
            LogUtil.d(TAG, "file " + file.getAbsolutePath());

            if (file.exists()) {
                LogUtil.d(TAG, "图片存在，开始加载 " + file.getAbsolutePath());
//                decodedPhoto = IOHelper.loadBitmap(file.getAbsolutePath());
                try {
                    decodedPhoto = IOHelper.big(file.getAbsolutePath());
                } catch (Exception e) {
                    e.printStackTrace();
                }
            } else {
                LogUtil.d(TAG, "图片不存在");
            }

            return decodedPhoto;
        }


        @Override
        public void onPostExecute(Bitmap photo) {
            if (photo != null) {
                LogUtil.d(TAG, "mBitmapStore " + mBitmapStore.size());
                try {
                    mBitmapStore.put(mBitmapPath.get(mIndex).getName(), photo);

                    if (mImageViewer != null) {
                        mImageViewer.setBitmap(photo);
                    } else {
                        LogUtil.d(TAG, "mImageViewer 为空 " + mImageViewer);
                    }


                    int tempIndex = mIndex - 1;
                    if (tempIndex >= 0 && mBitmapPath.get(tempIndex).getName() != null) {
                        Bitmap old = mBitmapStore.get(mBitmapPath.get(tempIndex).getName());
                        if (old != null) {
                            old.recycle();
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {

                }

            }
            if (mBitmapPath.size() > 1) {

                mHandler.postDelayed(mFlipper, mDropPeriod);
            }
        }
    }


}
