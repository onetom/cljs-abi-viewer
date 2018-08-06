(ns state-channel.demo
  (:require [com.rpl.specter :as s
             :refer [NIL->SET VAL END NONE-ELEM NONE MAP-KEYS
                     keypath subset set-elem path]
             :refer-macros [select select-one transform setval]]
            [cljs.dom
             :refer [Elem render log mount $ text elem frag fragment
                     div span i b img br hr h1 h2 h3 h4 hr ul li table tr th td
                     v-array h-array v-map h-map x xi robohash]
             :as dom]))

(defrecord Party [nickname])

(extend-type Party
  Elem
  (render [{:keys [nickname]}]
    (span {:class "party"}
          (img {:src (robohash nickname 3)})
          ;(br)
          nickname))

  IPrintWithWriter
  (-pr-writer [party writer _opts]
    (-write writer (:nickname party))))

(def Alice (->Party "Alice"))
(def Bob (->Party "Bob"))

(defrecord ChannelSide [party deposit credit withdrawal])

(defrecord Channel [channel-id round left right signed])

(extend-type Channel dom/Elem
  (render [{:keys [channel-id round left right signed] :as ch}]
    (let [side-row (fn [{:keys [party deposit credit withdrawal]}]
                     (tr (th {} party) (td deposit) (td credit) (td withdrawal)))]
      (table (if signed {:class "signed"} {})
             (tr (td (i (str "chID: " channel-id)) (br)
                     (str "Round: " round))
                 (th "Deposit") (th "Credit") (th "Withdrawal"))
             (side-row left)
             (side-row right)))))

(defrecord System [])
(extend-type System Elem
  (render [sys] (v-map sys)))

(def initial-state
  (map->System
    {Alice             {:channel nil}
     :channel-registry {:channel-counter 0
                        :channels        {}}
     :weth             {:balanceOf {Alice      50
                                    Bob        80
                                    "Registry" 0}}
     Bob               {:channel nil}}))

; ============  Paths and helper functions  ==============

(defn dec-by [by amount] (- amount by))
(defn inc-by [by amount] (+ amount by))

(defn weth-balance [party]
  (path [:weth :balanceOf (keypath party)]))

(defn chain-channel [channel-id]
  (path [:channel-registry :channels channel-id]))

(defn channel-balance [ch side]
  (let [{:keys [deposit credit withdrawal]} (-> ch side)]
    (-> deposit
        (+ credit)
        (- withdrawal))))

(defn channel-id [state party]
  (-> state (get party) :channel :channel-id))

; ============  Actions  ==============

(defn open-channel [state party1 party2]
  (let [channel-id (-> state :channel-registry :channel-counter)
        channel    (map->Channel {:channel-id channel-id
                                  :round      -1
                                  :left       (map->ChannelSide
                                                {:party      party1
                                                 :deposit    0
                                                 :credit     0
                                                 :withdrawal 0})
                                  :right      (map->ChannelSide
                                                {:party      party2
                                                 :deposit    0
                                                 :credit     0
                                                 :withdrawal 0})})]
    (->> state
         (transform [:channel-registry :channels] #(assoc % channel-id channel))
         (transform [:channel-registry :channel-counter] inc)
         (setval [(keypath party1) :channel] channel)
         (setval [(keypath party2) :channel] channel))))

(defn deposit-plan [state party channel-id amount]
  (->> state
       (transform [(keypath party) :channel :left :deposit]
                  (partial inc-by amount))
       (transform [(keypath party) :channel :round] inc)))

(defn transfer-plan [state src dst channel-id amount]
  (->> state
       (transform [(keypath src) :channel :left :credit]
                  (partial dec-by amount))
       (transform [(keypath src) :channel :right :credit]
                  (partial inc-by amount))
       (transform [(keypath src) :channel :round] inc)))

(defn deposit [state party channel-id amount]
  (->> state
       (transform (weth-balance party) (partial dec-by amount))
       (transform (weth-balance "Registry") (partial inc-by amount))
       (transform [(chain-channel channel-id) :left :deposit] (partial inc-by amount))
       (transform [(chain-channel channel-id) :round] inc)))

; ============  Steps  ==============

(defn step-open-channel [state]
  (-> state
      (open-channel Alice Bob)))

(defn step-deposit-plan [state]
  (-> state
      (deposit-plan Alice (channel-id state Alice) 10)))

(defn step-deposit [state]
  (-> state
      (deposit Alice (channel-id state Alice) 10)))

(defn step-deposit-event [state]
  (->> state
       (setval [(keypath Bob) :channel]
               (select-one (chain-channel (channel-id state Alice)) state))))

(defn step-transfer-plan [state]
  (-> state
      (transfer-plan Alice Bob (channel-id state Alice) 7)))

(defn step-transfer [state]
  (->> state
       (setval [(keypath Bob) :channel]
               (select-one [(keypath Alice) :channel] state))))

(defn step-update-plan [state]
  (let [alice-ch      (path [(keypath Alice) :channel])
        alice-channel (select-one alice-ch state)]
    (->> state
         (setval [alice-ch :left :withdrawal]
                 (channel-balance alice-channel :left))
         (setval [alice-ch :right :withdrawal]
                 (channel-balance alice-channel :right))
         (transform [alice-ch :round] inc)
         (setval [alice-ch :signed] true))))

(defn step-send-update-plan [state]
  (->> state
       (setval [(keypath Bob) :channel]
               (select-one [(keypath Alice) :channel] state))))

(defn step-update [state]
  (let [bob-ch (-> (select-one [(keypath Bob) :channel] state)
                   (assoc :signed false))]
    (->> state
         (setval [(chain-channel (-> bob-ch :channel-id))]
                 bob-ch))))

(defn step-alice-withdraw [state]
  (let [alice-ch (select-one [(chain-channel (channel-id state Alice)) :left] state)]
    (->> state
         (transform [(weth-balance Alice)]
                    (partial inc-by (:withdrawal alice-ch))))))

(defn step-bob-withdraw [state]
  (let [bob-ch (select-one [(chain-channel (channel-id state Alice)) :right] state)]
    (->> state
         (transform [(weth-balance Bob)]
                    (partial inc-by (:withdrawal bob-ch))))))

; TODO
;   Non-cooperative withdrawal
;   Tokens
;   Conditional payment

(def steps
  (->> [#'step-open-channel
        #'step-deposit-plan
        #'step-deposit
        #'step-deposit-event
        #'step-transfer-plan
        #'step-transfer
        #'step-update-plan
        #'step-send-update-plan
        #'step-update
        #'step-alice-withdraw
        #'step-bob-withdraw]
       (reductions (fn [state xform]
                     (-> state xform (assoc :xform (-> xform meta :name))))
                   initial-state)
       vec))
