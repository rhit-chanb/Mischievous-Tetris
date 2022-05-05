import socket
from time import sleep


def main():
    print("Match")
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.bind(('localhost',1111))
    server.listen()
    clientList = []
    while(True):
        client, address = server.accept()
        addr = address[0]
        port = int(client.recv(2048).decode())
        clientList.append((addr,port))
        # print(clientList)
        for connection in clientList:
            print(connection," : ",(addr,port))
            if connection != (addr,port):
                client.send(connection[0].encode())
                sleep(1)
                client.send(str(connection[1]).encode())
        sleep(1)
        client.send('n'.encode())
        client.close()


if __name__ == "__main__":
    main()