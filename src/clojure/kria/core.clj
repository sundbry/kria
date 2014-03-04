(ns kria.core
  (:require
    [kria.conversions :refer :all]
    [kria.pb.error :refer [bytes->ErrorResp]])
  (:import
    [java.nio ByteBuffer]
    [java.nio.channels AsynchronousSocketChannel CompletionHandler]
    [com.basho.riak.protobuf RiakPB$RpbErrorResp]
    [com.google.protobuf InvalidProtocolBufferException]))

(set! *warn-on-reflection* true)

(def ^:const message-codes
  {:error-resp 0
   :ping-req 1
   :ping-resp 2
   :get-client-id-req 3
   :get-client-id-resp 4
   :set-client-id-req 5
   :set-client-id-resp 6
   :get-server-info-req 7
   :get-server-info-resp 8
   :get-req 9
   :get-resp 10
   :put-req 11
   :put-resp 12
   :del-req 13
   :del-resp 14
   :list-buckets-req 15
   :list-buckets-resp 16
   :list-keys-req 17
   :list-keys-resp 18
   :get-bucket-req 19
   :get-bucket-resp 20
   :set-bucket-req 21
   :set-bucket-resp 22
   :map-red-req 23
   :map-red-resp 24
   :index-req 25
   :index-resp 26
   :search-query-req 27
   :search-query-resp 28
   :reset-bucket-req 29
   :reset-bucket-resp 30
   :counter-update-req 50
   :counter-update-resp 51
   :counter-get-req 52
   :counter-get-resp 53
   :yz-index-get-req 54
   :yz-index-get-resp 55
   :yz-index-put-req 56
   :yz-index-put-resp 12 ; *
   :yz-index-del-req 57
   :yz-index-del-resp 14 ; *
   :yz-schema-get-req 58
   :yz-schema-get-resp 59
   :yz-schema-put-req 60
   :yz-schema-put-resp 12 ; *
   :dt-fetch-req 80
   :dt-fetch-resp 81
   :dt-update-req 82
   :dt-update-resp 83})
; * https://github.com/basho/riak-java-client/issues/367

(defn ^Byte message-code-byte
  "Returns a Riak message code as a byte."
  [message-key]
  {:pre [(keyword? message-key)]}
  (byte (message-key message-codes)))

(defn simple-message
  "Returns a ByteBuffer message suitable for Riak."
  [message-key]
  {:pre [(keyword? message-key)]}
  (doto (ByteBuffer/allocate 5)
    (.putInt 1)
    (.put (message-code-byte message-key))
    (.rewind)))

(defn payload-message
  "Returns a ByteBuffer message suitable for Riak."
  [message-key ^bytes payload]
  (let [payload-length (count payload)
        body-length (inc payload-length)
        message-length (+ 4 body-length)]
    (doto (ByteBuffer/allocate message-length)
      (.putInt body-length)
      (.put (message-code-byte message-key))
      (.put payload)
      (.rewind))))

(defn length-and-code
  "Returns [length, code]. Mutates the supplied buffer, but the
  combined effects should cancel out."
  [^ByteBuffer buf]
  (.rewind buf)
  (vector (.getInt buf) (.get buf)))

(defn read-header-handler
  "Returns a CompletionHandler..."
  [asc cb]
  {:pre [(fn? cb)]}
  (proxy [CompletionHandler] []
    (completed [n buf] (cb asc nil (length-and-code buf)))
    (failed [e buf] (cb asc e (length-and-code buf)))))

(defn read-header
  [^AsynchronousSocketChannel asc cb]
  {:pre [(fn? cb)]}
  (let [buf (ByteBuffer/allocate 5)]
    (.read asc buf buf (read-header-handler asc cb))))

(defn handler
  "Returns a CompletionHandler..."
  [asc cb]
  {:pre [(fn? cb)]}
  (proxy [CompletionHandler] []
    (completed [r a] (read-header asc cb))
    (failed [e a] (cb asc e nil))))

(defn read-payload-handler
  "Returns a CompletionHandler..."
  [asc len cb]
  {:pre [(integer? len) (fn? cb)]}
  (proxy [CompletionHandler] []
    (completed
      [n ^ByteBuffer buf]
      (if (= n len)
        (cb asc nil (.array buf))
        (cb asc {:length {:actual n :expected len}} nil)))
    (failed
      [e buf]
      (cb e nil))))

(defn read-payload
  "Reads a protobuf payload of length `len`. Note that this length
  is one less than the message length field."
  [^AsynchronousSocketChannel asc len cb]
  {:pre [(integer? len) (fn? cb)]}
  (let [buf (ByteBuffer/allocate len)]
    (.read asc buf buf (read-payload-handler asc len cb))))

(defn parse-fn
  "Returns a parse function, which itself can serve as a callback.
  Will call the provided callback correctly."
  [f cb]
  {:pre [(fn? f) (fn? cb)]}
  (fn [asc e a]
    (try
      (cb asc nil (f a))
      (catch InvalidProtocolBufferException e
        (cb asc e nil)))))

(defn error-parse-fn
  "Returns a parse function, which itself can serve as a callback.
  Will call the provided callback correctly."
  [cb]
  (fn [asc e a]
    (try
      (let [resp (bytes->ErrorResp a)
            error {:message (:message resp) :code (:code resp)}]
        (cb asc error nil))
      (catch InvalidProtocolBufferException e
        (cb asc e nil)))))

(defn header-cb-fn
  "Returns a header callback function based on an expected message
  key, a parser function, and a callback function."
  [exp-key parser cb]
  {:pre [(keyword? exp-key) (fn? parser) (fn? cb)]}
  (let [exp (exp-key message-codes)
        err (:error-resp message-codes)]
    (fn [asc e [l c]]
      (if e
        (cb asc e nil)
        (cond
          (= c exp) (read-payload asc (dec l) parser)
          (= c err) (read-payload asc (dec l) (error-parse-fn cb))
          :else (cb asc {:code {:actual c :expected exp}} nil))))))

(defn call
  "A template function to call API via the protobuf interface.

  Parameters:
  * AsynchronousSocketChannel
  * callback function
  * request message key
  * response message key
  * function to convert a request map to a request byte array
  * function to convert a response byte array to a response map
  * the request map (constructed from function arguments)"
  [^AsynchronousSocketChannel asc cb req-key resp-key
   req-map->bytes bytes->resp-map req-map]
  {:pre [(fn? cb) (keyword? req-key) (keyword? resp-key)
         (fn? req-map->bytes) (fn? bytes->resp-map)
         (map? req-map)]}
  (let [payload (req-map->bytes req-map)
        message (payload-message req-key payload)
        parser (parse-fn bytes->resp-map cb)
        header-cb (header-cb-fn resp-key parser cb)
        handler (handler asc header-cb)]
    (.write asc message nil handler)))