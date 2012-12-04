package com.silverkeytech.android_rivers

import android.app.Activity
import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.AsyncTask
import android.os.Handler
import android.os.Message
import android.os.Messenger
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Adapter
import android.widget.AdapterView
import android.widget.AdapterView.OnItemClickListener
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import com.github.kevinsawicki.http.HttpRequest
import com.github.kevinsawicki.http.HttpRequest.HttpRequestException
import com.google.gson.Gson
import com.silverkeytech.android_rivers.riverjs.FeedItemMeta
import com.silverkeytech.android_rivers.riverjs.FeedSite
import com.silverkeytech.android_rivers.riverjs.FeedsRiver
import java.util.ArrayList

//Responsible for handling a river js downloading and display in asynchronous way
public class DownloadRiverContent(it: Context?, ignoreCache : Boolean): AsyncTask<String, Int, Result<FeedsRiver>>(){
    class object {
        public val TAG: String = javaClass<DownloadRiverContent>().getSimpleName()
    }

    var dialog: ProgressDialog = ProgressDialog(it)
    var context: Activity = it!! as Activity
    val ignoreCache : Boolean = ignoreCache

    val STANDARD_NEWS_COLOR = android.graphics.Color.GRAY
    val STANDARD_NEWS_IMAGE = android.graphics.Color.CYAN
    val STANDARD_NEWS_PODCAST = android.graphics.Color.MAGENTA

    //Prepare stuff before execution
    protected override fun onPreExecute() {
        dialog.setMessage(context.getString(R.string.please_wait_while_loading))
        dialog.setIndeterminate(true)
        dialog.setCancelable(false)
        dialog.setButton(DialogInterface.BUTTON_NEGATIVE, context.getString(android.R.string.cancel), object : DialogInterface.OnClickListener{
            public override fun onClick(p0: DialogInterface?, p1: Int) {
                p0!!.dismiss()
                this@DownloadRiverContent.cancel(true)
            }
        })

        dialog.show()
    }

    //Download river data in a thread
    protected override fun doInBackground(vararg p0: String?): Result<FeedsRiver>? {
        try{
            var url = p0.get(0)!!
            var cache = context.getApplication().getMain().getRiverCache(url)

            if (cache != null && !ignoreCache){
                Log.d(TAG, "Cache is hit for url $url")
                return Result.right(cache!!)
            }
            else {
                Log.d(TAG, "Cache is missed for url $url")

                var req: String?
                try{
                    req = HttpRequest.get(url)?.body()
                }
                catch(e: HttpRequestException){
                    var ex = e.getCause()
                    return Result.wrong(ex)
                }

                try{
                    var gson = Gson()
                    var scrubbed = scrubJsonP(req!!)
                    var feeds = gson.fromJson(scrubbed, javaClass<FeedsRiver>())!!

                    context.getApplication().getMain().setRiverCache(url, feeds)

                    return Result.right(feeds)
                }
                catch(e: Exception)
                {
                    return Result.wrong(e)
                }
            }
        } catch(e: Exception)
        {
            return Result.wrong(e)
        }
    }

    //Once the thread is done.
    protected override fun onPostExecute(result: Result<FeedsRiver>?) {
        dialog.dismiss();
        if (result == null)
            context.toastee("Sorry, we cannot process this feed because the operation is cancelled", Duration.AVERAGE)
        else {
            if (result.isFalse()){
                var error = ConnectivityErrorMessage(
                        timeoutException = "Sorry, we cannot download this feed. The feed site might be down.",
                        socketException = "Sorry, we cannot download this feed. Please check your Internet connection, it might be down.",
                        otherException = "Sorry, we cannot download this feed for the following technical reason : ${result.exception.toString()}"
                )
                context.handleConnectivityError(result.exception, error)
            }else{
                handleNewsListing(result.value!!)
            }
        }
    }

    //hold the view data for the list
    data class ViewHolder (var news: TextView, val indicator: TextView)

    //show and prepare the interaction for each individual news item
    fun handleNewsListing(river: FeedsRiver) {
        var newsItems = ArrayList<FeedItemMeta>()

        //Take all the news items from several different feeds and combine them into one.
        for(var f : FeedSite? in river.updatedFeeds?.updatedFeed?.iterator()){
            if (f != null){
                for(var fi in f?.item?.iterator()){
                    if (fi != null) {
                        newsItems.add(FeedItemMeta(fi!!, f?.feedTitle, f?.feedUrl))
                    }
                }
            }
        }

        //now sort it so people always have the latest news first
        var sortedNewsItems = newsItems.filter { x -> x.item.isPublicationDate()!! }.sortBy { x -> x.item.getPublicationDate()!! }.reverse()

        var list = context.findView<ListView>(android.R.id.list)

        var adapter = object : ArrayAdapter<FeedItemMeta>(context, R.layout.news_item, sortedNewsItems) {
            public override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View? {
                var currentView = convertView
                var holder: ViewHolder?

                var currentNewsItem = sortedNewsItems[position]
                var news = currentNewsItem.item.toString()?.trim()

                if (news == null)
                    news = ""

                fun showIndicator() {
                    if (currentNewsItem.item.containsEnclosure()!!){
                        var enclosure = currentNewsItem.item.enclosure!!.get(0)!!
                        if (isSupportedImageMime(enclosure.`type`!!)){
                            holder?.indicator?.setBackgroundColor(STANDARD_NEWS_IMAGE)
                        }
                        else{
                            holder?.indicator?.setBackgroundColor(STANDARD_NEWS_PODCAST)
                        }
                    }else{
                        holder?.indicator?.setBackgroundColor(STANDARD_NEWS_COLOR)
                    }
                }

                if (currentView == null){
                    var inflater: LayoutInflater = context.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
                    currentView = inflater.inflate(R.layout.news_item, parent, false)

                    holder = ViewHolder(currentView!!.findViewById(R.id.news_item_text_tv) as TextView,
                            currentView!!.findViewById(R.id.news_item_indicator_tv) as TextView)

                    holder?.news?.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24.toFloat())
                    currentView!!.setTag(holder)
                }else{
                    holder = currentView?.getTag() as ViewHolder
                    Log.d(TAG, "List View reused")
                }

                holder?.news?.setText(news)
                showIndicator()

                if (news.isNullOrEmpty()){
                    currentView?.setVisibility(View.GONE)
                }   else{
                    currentView?.setVisibility(View.VISIBLE)
                }
                return currentView
            }
        }

        list.setOnItemClickListener(object : OnItemClickListener{
            public override fun onItemClick(p0: AdapterView<out Adapter?>?, p1: View?, p2: Int, p3: Long) {
                var currentNews = sortedNewsItems.get(p2);

                var dialog = AlertDialog.Builder(context)

                if (currentNews.item.body.isNullOrEmpty() && !currentNews.item.title.isNullOrEmpty())
                    dialog.setMessage(scrubHtml(currentNews.item.title))
                else
                    dialog.setMessage(scrubHtml(currentNews.item.body))

                //Check dismiss button
                dialog.setPositiveButton(android.R.string.ok, object : DialogInterface.OnClickListener{
                    public override fun onClick(p0: DialogInterface?, p1: Int) {
                        p0?.dismiss()
                    }
                })

                val newItemLinkAvailable : Boolean = !currentNews.item.link.isNullOrEmpty() && currentNews.item.link!!.indexOf("http") > -1

                //check for go link
                if (newItemLinkAvailable){
                    dialog.setNeutralButton("Go", object : DialogInterface.OnClickListener{
                        public override fun onClick(p0: DialogInterface?, p1: Int) {
                            var i = Intent("android.intent.action.VIEW", Uri.parse(currentNews.item.link))
                            context.startActivity(i)
                            p0?.dismiss()
                        }
                    })
                }

                //check for image enclosure
                if (currentNews.item.containsEnclosure()!! ){
                    var enclosure = currentNews.item.enclosure!!.get(0)!!
                    if (isSupportedImageMime(enclosure.`type`!!)){
                        dialog.setNegativeButton("Image", object : DialogInterface.OnClickListener{
                            public override fun onClick(p0: DialogInterface?, p1: Int) {
                                Log.d(TAG, "I am downloading a ${enclosure.url} with type ${enclosure.`type`}")
                                DownloadImage(context).execute(enclosure.url)
                            }
                        })
                    }
                    //assume podcast
                    else {
                        dialog.setNegativeButton("Podcast", object : DialogInterface.OnClickListener{
                            public override fun onClick(p0: DialogInterface?, p1: Int) {
                                var messenger = Messenger(object : Handler(){
                                    public override fun handleMessage(msg: Message?) {
                                        if (msg != null){
                                            var path = msg.obj as String

                                            if (msg.arg1 == Activity.RESULT_OK && !path.isNullOrEmpty()){
                                                context.toastee("File is successfully downloaded at $path", Duration.LONG)
                                                MediaScannerWrapper.scanPodcasts(context, path)
                                            }else{
                                                context.toastee("Download failed", Duration.LONG)
                                            }
                                        }else{
                                            Log.d(TAG, "handleMessage returns null, which is very weird")
                                        }
                                    }
                                })

                                var intent = Intent(context, javaClass<DownloadService>())
                                intent.putExtra(DownloadService.PARAM_DOWNLOAD_URL, enclosure.url)
                                intent.putExtra(DownloadService.PARAM_DOWNLOAD_TITLE, currentNews.item.title)
                                intent.putExtra(Params.MESSENGER, messenger)
                                context.startService(intent)
                            }
                        })
                    }
                }else{ //there is not enclosure detected, so enable sharing
                    if (newItemLinkAvailable) {
                        dialog.setNegativeButton("Share", object : DialogInterface.OnClickListener{
                            public override fun onClick(p0: DialogInterface?, p1: Int) {
                                var intent = Intent(Intent.ACTION_SEND)
                                intent.setType("text/plain")
                                intent.putExtra(Intent.EXTRA_TEXT, currentNews.item.link!!)
                                context.startActivity(Intent.createChooser(intent, "Share page"))
                            }
                        })
                    }
                }

                dialog.create()!!.show()
            }
        })

        list.setAdapter(adapter)
    }
}