            (function(){
              if(window.__tsFootFix){return true;}
              window.__tsFootFix=true;
              function readCacheList(){
                try{
                  var raw=window.localStorage.getItem('forumTab.forumData');
                  if(!raw){return null;}
                  var o=JSON.parse(raw);
                  var h=o&&o.historyForumServer;
                  return (h&&h.length)?h:null;
                }catch(e){return null;}
              }
              function readBackup(){
                try{
                  var raw=window.localStorage.getItem('__tsFootBackup');
                  if(!raw){return null;}
                  var a=JSON.parse(raw);
                  return (a&&a.length)?a:null;
                }catch(e){return null;}
              }
              function writeBackup(a){
                try{if(a&&a.length){window.localStorage.setItem('__tsFootBackup',JSON.stringify(a));}}catch(e){}
              }
              function seedList(){return readCacheList()||readBackup();}
              try{
                var c=readCacheList();
                if(c){writeBackup(c);}
                var oS=window.localStorage.setItem.bind(window.localStorage);
                window.localStorage.setItem=function(k,v){
                  try{
                    if(String(k)==='forumTab.forumData'&&v){
                      var o=JSON.parse(v);
                      var h=(o&&o.historyForumServer)||[];
                      if(h.length){writeBackup(h);}
                      else{
                        var b=readBackup();
                        if(b){o.historyForumServer=b;v=JSON.stringify(o);}
                      }
                    }
                  }catch(e){}
                  return oS(k,v);
                };
              }catch(e){}
              function fixBody(b){
                try{
                  if(String(b).indexOf('history=%5B%5D')>=0){
                    var src=seedList();
                    if(src){
                      var list=[];
                      for(var i=0;i<src.length;i++){
                        list.push({forum_id:String(src[i].forum_id),visit_time:String(src[i].visit_time)});
                      }
                      return String(b).split('history=%5B%5D').join('history='+encodeURIComponent(JSON.stringify(list)));
                    }
                  }
                }catch(e){}
                return b;
              }
              try{
                var oOpen=XMLHttpRequest.prototype.open,oSend=XMLHttpRequest.prototype.send;
                XMLHttpRequest.prototype.open=function(m,u){this.__tsFootUrl=String(u);return oOpen.apply(this,arguments);};
                XMLHttpRequest.prototype.send=function(b){
                  try{
                    if(this.__tsFootUrl&&this.__tsFootUrl.indexOf('gethistoryforum')>=0&&b!=null){
                      var nb=fixBody(String(b));
                      if(nb!==b){return oSend.call(this,nb);}
                    }
                  }catch(e){}
                  return oSend.apply(this,arguments);
                };
              }catch(e){}
              return false;
            })();
