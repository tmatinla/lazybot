(ns sexpbot.registry
  (:use [sexpbot.utilities :only [thunk-timeout]])
  (:require [irclj.core :as ircb])
  (:import java.util.concurrent.TimeoutException))

(defmacro def- [name & value]
  (concat (list 'def (with-meta name (assoc (meta name) :private true))) value))

(defn nil-comp [com bot channel s action? & fns]
  (reduce #(when %1
             (%2 com bot channel %1 action?))
          s fns))

(defn pull-hooks [bot hook-key]
  (hook-key (apply merge-with concat (map :hooks (vals (:modules @bot))))))

(defn call-message-hooks [com bot channel s action?]
  (apply nil-comp com bot channel s action? (pull-hooks bot :on-send-message)))

(defmulti send-message (fn [m & rest] (-> m :bot deref :protocol)))

(defmethod send-message "irc"
  [{:keys [com bot channel]} s & {:keys [action? notice?]}]
  (if-let [result (call-message-hooks com bot channel s action?)]
    (cond
     action? (ircb/send-action com channel result)
     notice? (ircb/send-notice com channel result)
     :else (ircb/send-message com channel result))))

(defn get-priv [logged-in user]
  (if (and (seq logged-in) (-> user logged-in (= :admin))) :admin :noadmin))

(defmacro if-admin
  [user full bot & body]
  `(let [com# (:com ~full)]
     (if (and (seq (:logged-in @~bot))
              (= :admin (get-priv ((:logged-in @~bot) (:server @com#)) ~user)))
       (do ~@body)
       (send-message ~full (str ~user ": You aren't an admin!")))))

(defn find-command [modules command]
  (some #(when ((:triggers %) command) %) (apply concat (map :commands (vals modules)))))

(defn find-docs [bot command]
  (:docs (find-command (:modules @bot) command)))

(defn respond [{:keys [command bot]}]
  (or (:fn (find-command (:modules @bot) command)) (constantly nil)))

(defn full-prepend [config s]
  ((:prepends config) s))

(defn m-starts-with [m s]
  (some identity (map #(.startsWith m %) s)))

(defn split-args [config s pm?]
  (let [[prepend command & args] (.split s " ")
        is-long-pre (full-prepend config prepend)
        prefix (or (full-prepend config prepend)
                   (->> prepend first str (full-prepend config)))]
    {:command (cond
               is-long-pre command
               prefix (apply str (rest prepend))
               pm? prepend)
     :args (if is-long-pre args (when command (conj args command)))}))

(defn is-command?
  "Tests whether or not a message begins with a prepend."
  [message bot]
  (m-starts-with message (-> @bot :config :prepends)))

(defn try-handle [{:keys [nick channel com bot message] :as com-map}]
  (.start
   (Thread.
    (fn []
      (let [bot-map (assoc com-map :privs (get-priv (:logged-in @bot) nick))
	    conf (:config @bot)
	    max-ops (:max-operations conf)
            pm? (= nick channel)]
	(when (or (is-command? message bot) pm?)
	  (if (dosync
	       (let [pending (:pending-ops @bot)
                     permitted (< pending max-ops)]
		 (when permitted
		   (alter bot assoc :pending-ops (inc pending)))))
	    (try
		 (let [n-bmap (into bot-map (split-args conf message pm?))]
		   (thunk-timeout #((respond n-bmap) n-bmap) 30))
		 (catch TimeoutException _ (send-message com-map "Execution timed out."))
		 (catch Exception e (.printStackTrace e))
		 (finally (dosync
			   (alter bot assoc :pending-ops (dec (:pending-ops @bot))))))
	    (send-message com-map "Too much is happening at once. Wait until other operations cease."))))))))

(defn merge-with-conj [& args]
  (apply merge-with #(if (vector? %) (conj % %2) (conj [] % %2)) args))

(defn parse-fns [body]
  (apply merge-with-conj
         (for [[one two three four] body]
           (case
            one
            :cmd {:cmd {:docs two :triggers three :fn four}}
            :hook {:hook {two three}}
            :cleanup {:cleanup two}
            :init {:init two}
            :routes {:routes two}))))

(defn if-seq-error [fn-type possible-seq]
  (if (and (not (fn? possible-seq)) (seq possible-seq))
    (throw (Exception. (str "Only one " fn-type " function allowed.")))
    possible-seq))

(defmacro defplugin [& body]
  (let [{:keys [cmd hook cleanup init routes]} (parse-fns body)
        scmd (if (map? cmd) [cmd] cmd)]
    `(let [pns# *ns*]
       (defn ~'load-this-plugin [com# bot#]
         (when ~init ((if-seq-error "init" ~init) com# bot#))
         (let [m-name# (keyword (last (.split (str pns#) "\\.")))]
           (dosync
            (alter bot# assoc-in [:modules m-name#]
                   {:commands ~scmd
                    :hooks (into {}
                                 (map (fn [[k# v#]] (if (vector? v#) [k# v#] [k# [v#]]))
                                      (apply merge-with-conj (if (vector? ~hook) ~hook [~hook]))))
                    :cleanup (if-seq-error "cleanup" ~cleanup)
                    :routes ~routes})))))))