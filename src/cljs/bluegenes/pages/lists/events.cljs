(ns bluegenes.pages.lists.events
  (:require [re-frame.core :refer [reg-event-db reg-event-fx]]
            [re-frame.std-interceptors :refer [path]]
            [bluegenes.pages.lists.utils :refer [denormalize-lists path-prefix? internal-tag? split-path join-path list->path]]
            [imcljs.save :as save]
            [clojure.set :as set]))

(def root [:lists])

;; A hash-map is more amenable to locating specific lists, so we copy the
;; vector of lists into a id->list map.
(reg-event-db
 :lists/initialize
 (fn [db]
   (let [all-lists (get-in db [:assets :lists (:current-mine db)])
         all-tags (->> all-lists (mapcat :tags) distinct)]
     (update-in db root assoc
                :by-id (denormalize-lists all-lists)
                :all-tags (->> all-tags (remove internal-tag?) sort)
                :all-types (->> all-lists (map :type) distinct sort)
                :all-paths (->> (filter path-prefix? all-tags)
                                (map (comp #(subvec % 1) split-path)))))))

(reg-event-db
 :lists/expand-path
 (path root)
 (fn [lists [_ path]]
   (update lists :expanded-paths (fnil conj #{}) path)))

(reg-event-db
 :lists/collapse-path
 (path root)
 (fn [lists [_ path]]
   (update lists :expanded-paths (fnil disj #{}) path)))

;; Note: Do not dispatch this from more than one place.
;; The input field which changes this value uses debouncing and internal state,
;; so it won't sync with this value except when first mounting.
(reg-event-db
 :lists/set-keywords-filter
 (path root)
 (fn [lists [_ keywords-string]]
   (assoc-in lists [:controls :filters :keywords] keywords-string)))

(reg-event-db
 :lists/toggle-sort
 (path root)
 (fn [lists [_ column]]
   (update-in lists [:controls :sort]
              (fn [{old-column :column old-order :order}]
                {:column column
                 :order (if (= old-column column)
                          (case old-order
                            :asc :desc
                            :desc :asc)
                          :asc)}))))

(reg-event-db
 :lists/set-filter
 (path root)
 (fn [lists [_ filter-name value]]
   (assoc-in lists [:controls :filters filter-name] value)))

(reg-event-db
 :lists/select-list
 (path root)
 (fn [lists [_ list-id]]
   (update lists :selected-lists (fnil conj #{}) list-id)))

(reg-event-db
 :lists/deselect-list
 (path root)
 (fn [lists [_ list-id]]
   (if (= :subtract (get-in lists [:modal :active]))
     ;; We need to remove list-id from more places if subtract modal is active.
     (-> lists
         (update :selected-lists (fnil disj #{}) list-id)
         (update-in [:modal :keep-lists] (partial filterv #(not= list-id %)))
         (update-in [:modal :subtract-lists] (partial filterv #(not= list-id %))))
     (update lists :selected-lists (fnil disj #{}) list-id))))

(reg-event-db
 :lists/clear-selected
 (path root)
 (fn [lists [_]]
   (assoc lists :selected-lists #{})))

(reg-event-db
 :lists/open-modal
 (path root)
 (fn [lists [_ modal-kw]]
   (if (= modal-kw :subtract)
     ;; Subtract modal needs some prepared data.
     (let [selected-lists (vec (:selected-lists lists))]
       (assoc lists :modal
              {:active modal-kw
               :open? true
               :keep-lists (pop selected-lists)
               :subtract-lists (vector (peek selected-lists))}))
     (assoc lists :modal
            {:active modal-kw
             :open? true}))))

(reg-event-db
 :lists/close-modal
 (path root)
 (fn [lists [_]]
   (assoc-in lists [:modal :open?] false)))

(reg-event-db
 :lists-modal/set-new-list-tags
 (path root)
 (fn [lists [_ tags]]
   (assoc-in lists [:modal :tags] tags)))

(reg-event-db
 :lists-modal/set-new-list-title
 (path root)
 (fn [lists [_ title]]
   (assoc-in lists [:modal :title] title)))

(reg-event-db
 :lists-modal/set-new-list-description
 (path root)
 (fn [lists [_ description]]
   (assoc-in lists [:modal :description] description)))

(reg-event-db
 :lists-modal/subtract-list
 (path root)
 (fn [lists [_ id]]
   (-> lists
       (update-in [:modal :keep-lists] (partial filterv #(not= id %)))
       (update-in [:modal :subtract-lists] conj id))))

(reg-event-db
 :lists-modal/keep-list
 (path root)
 (fn [lists [_ id]]
   (-> lists
       (update-in [:modal :keep-lists] conj id)
       (update-in [:modal :subtract-lists] (partial filterv #(not= id %))))))

(reg-event-db
 :lists-modal/nest-folder
 (path root)
 (fn [lists [_ new-folder]]
   (update-in lists [:modal :folder-path] (fnil conj []) new-folder)))

(reg-event-db
 :lists-modal/denest-folder
 (path root)
 (fn [lists [_]]
   (update-in lists [:modal :folder-path] pop)))

(def list-operation->im-req
  {:combine save/im-list-union
   :intersect save/im-list-intersect
   :difference save/im-list-difference
   :subtract save/im-list-subtraction})

(reg-event-fx
 :lists/set-operation
 (fn [{db :db} [_ list-operation]]
   (let [service (get-in db [:mines (:current-mine db) :service])
         im-req (list-operation->im-req list-operation)
         {:keys [by-id selected-lists]
          {:keys [title tags description
                  keep-lists subtract-lists]} :modal} (:lists db)
         keep-lists     (->> keep-lists     (map by-id) (map :name))
         subtract-lists (->> subtract-lists (map by-id) (map :name))
         source-lists   (->> selected-lists (map by-id) (map :name))
         subtract? (= list-operation :subtract)
         enough-lists? (if subtract?
                         (every? seq [keep-lists subtract-lists])
                         (> (count source-lists) 1))
         options {:description description :tags tags}]
     (if (and im-req enough-lists? (not-empty title))
       {:im-chan {:chan (if subtract?
                          (im-req service title keep-lists subtract-lists options)
                          (im-req service title source-lists options))
                  :on-success [:lists/set-operation-success]
                  :on-failure [:lists/set-operation-failure title]}
        :db (assoc-in db [:lists :modal :error] nil)}
       {:db (assoc-in db [:lists :modal :error]
                      (cond
                        (not im-req) (str "Invalid list operation: " list-operation)
                        (and (not enough-lists?) subtract?) "You need at least 1 list in each set to perform a list subtraction"
                        (not enough-lists?) "You need at least 2 lists to perform a list set operation"
                        (empty? title) "You need to specify a title for the new list"
                        :else "Unexpected error"))}))))

(reg-event-fx
 :lists/set-operation-success
 (fn [{db :db} [_ res]] ; Note that `res` can be nil or a collection of responses.
   {:dispatch-n [[:lists/close-modal]
                 [:lists/clear-selected]
                 [:assets/fetch-lists]]}))

(reg-event-fx
 :lists/set-operation-failure
 (fn [{db :db} [_ list-name res]]
   {:db (assoc-in db [:lists :modal :error]
                  (str "Failed to create list " list-name
                       (when-let [error (:error res)]
                         (str ": " error))))
    :log-error ["List set operation failure" res]}))

(reg-event-fx
 :lists/move-lists
 (fn [{db :db} [_]]
   (let [service (get-in db [:mines (:current-mine db) :service])
         {:keys [by-id selected-lists]
          {:keys [folder-path]} :modal} (:lists db)
         source-list-maps (map by-id selected-lists)
         new-list->tags (zipmap (map :name source-list-maps)
                                (repeat (join-path folder-path)))
         old-list->tags (zipmap (map :name source-list-maps)
                                (map list->path source-list-maps))
         update-tag-chans
         (->> (set/difference (set new-list->tags) (set old-list->tags))
              (map (fn [[list-name new-tag]]
                     (let [old-tag (get old-list->tags list-name)]
                       [(when old-tag (save/im-list-remove-tag service list-name old-tag))
                        (when new-tag (save/im-list-add-tag service list-name new-tag))])))
              (apply concat)
              (filter some?))]
     (cond
       (and (seq source-list-maps) (seq update-tag-chans))
       {:im-chan {:chans update-tag-chans
                  :on-success [:lists/set-operation-success]
                  :on-failure [:lists/move-lists-failure]}
        :db (assoc-in db [:lists :modal :error] nil)}

       (seq source-list-maps) ; Lists haven't actually moved around, so signal success.
       {:dispatch [:lists/set-operation-success nil]
        :db (assoc-in db [:lists :modal :error] nil)}

       :else
       {:db (assoc-in db [:lists :modal :error]
                      (cond
                        (empty? source-list-maps) "You need to select at least 1 list."
                        :else "Unexpected error"))}))))

(reg-event-fx
 :lists/move-lists-failure
 (fn [{db :db} [_ all-res]]
   {:db (assoc-in db [:lists :modal :error] "Error occured when moving lists")
    :dispatch [:assets/fetch-lists] ; In case some of them were moved successfully.
    :log-error ["Move lists failure" all-res]}))
