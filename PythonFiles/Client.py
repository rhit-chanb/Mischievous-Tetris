import socket
import sys
import threading
from time import sleep


def main():
    print("client")
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.bind(('localhost', int(sys.argv[1])))
    server.listen()

    matcher = socket.socket()
    matcher.connect(('localhost',1111))
    matcher.send(sys.argv[1].encode())
    connections = []
    while True:
        addr = matcher.recv(2048).decode()
        if addr == 'n':
            break
        port = int(matcher.recv(2048).decode())
        print(addr,port)
        conn = socket.socket()
        conn.connect((addr,port))
        print("connected to ",addr,port)
        threading.Thread(target=recvThread, args=(conn,)).start()
        connections.append(conn)
    matcher.close()

    threading.Thread(target=connThread,args=(server,connections,)).start()

    while True:
        msg = input()

        for connection in connections:
            print("sending",msg)
            connection.send(msg.encode())


def connThread(server, conns):
    while True:
        conn, addr = server.accept()
        print("connected to",addr)
        threading.Thread(target=recvThread,args=(conn,)).start()
        conns.append(conn)

def recvThread(conn):
    while True:
        msg = conn.recv(2048).decode()
        print(msg)

if __name__ == "__main__":
    main()